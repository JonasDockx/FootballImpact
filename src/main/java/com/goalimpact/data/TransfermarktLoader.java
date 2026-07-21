package com.goalimpact.data;

import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

// The Transfermarkt spine (ADR 0009). SQL does the set-shaped work -
// filtering, joining, the usability gate; Java owns every per-match
// judgement, so the domain rules stay unit-testable without a database
// and comparable against the StatsBomb loader that decides the same
// things from different evidence

public class TransfermarktLoader implements AutoCloseable {
    
    // A single match at a chosen ground is nobody's home fixture.
    // Two-legged finals are named "final 1st leg" / "final 2nd leg",
    // and are correctly untouched by an exact match on this.
    private static final String SINGLE_MATCH_FINAL = "Final";

    // One row of the games table, reduced to what the home-side rule
    // needs - so the rule stays a single testable function rather than a
    // six-argument call.
    record Fixture(String competitionId, String season, String competitionType,
        String round, long homeClubId, long awayClubId) {
    }

    private record Edition(String competitionId, String season) {
    }

    // Curated source facts (ADR 0009): a finals tournament is played at a
    // chosen host, which this snapshot records nowhere - it carries no
    // country for any club and no geography for any stadium. Only the
    // editions that ship lineups are listed; the rest cannot be replayed
    // at all. Values are Transfermarkt's own stable club ids.
    //
    // World Cup 2026 (FIWC/2025) is hosted by the USA, Canada and Mexico
    // at once, which this shape cannot express: it stays absent, so its
    // matches are neutral until per-match host rows exist.
    private static final Map<Edition, Long> TOURNAMENT_HOSTS = Map.of(
        new Edition("AFAC", "2024"), 14162L,   // Asian Cup 2024: Qatar
        new Edition("AFCN", "2025"), 3575L,    // AFCON 2025: Morocco
        new Edition("COPA", "2025"), 3505L);   // Copa America 2024: USA

    private record NeutralRound(String competitionId, String round) {
    }

    // Rounds a competition always plays at a chosen ground, whatever the
    // fixture label says. Scoped per competition on purpose: the FA Cup
    // has played its semi-finals at Wembley since 2008, while the
    // DFB-Pokal, KNVB Beker and Russian Cup play theirs at a club's own
    // ground - a blanket rule on the round name would be wrong in eleven
    // competitions to be right in one.
    private static final Set<NeutralRound> NEUTRAL_ROUNDS = Set.of(
        new NeutralRound("FAC", "Semi-Finals"));


    private final Connection connection;
    private long droppedEvents = 0; // events the source could not place

    // ADR 0009: everything skipped is counted, never printed and forgotten
    public long droppedEvents() {
        return droppedEvents;
    }

    public TransfermarktLoader(Path snapshot) throws SQLException {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        this.connection = DriverManager.getConnection("jdbc:duckdb:" + snapshot, readOnly);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    public List<Match> loadMatches(String competitionId, String season) throws SQLException {
        String sql = """
                SELECT game_id, date, round, competition_type,
                home_club_id, home_club_name,
                away_club_id, away_club_name,
                home_club_goals, away_club_goals

                FROM games
                WHERE competition_id = ? AND season = ?
                ORDER BY date, CAST(game_id AS BIGINT)
                """;
        List<Match> matches = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, competitionId);
            statement.setString(2, season);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Fixture fixture = new Fixture(competitionId, season,
                        rows.getString("competition_type"), rows.getString("round"),
                        rows.getLong("home_club_id"), rows.getLong("away_club_id"));
                    matches.add(new Match(
                        Long.parseLong(rows.getString("game_id")),
                        rows.getDate("date").toLocalDate(),
                        new Team(rows.getLong("home_club_id"), rows.getString("home_club_name")),
                        new Team(rows.getLong("away_club_id"), rows.getString("away_club_name")),
                        rows.getInt("home_club_goals"),
                        rows.getInt("away_club_goals"),
                        classifyHomeSide(fixture)));
                }
            }
        }
        return matches;
    }

    
    // Both lineup types: the starters form the XI, and every row supplies a
    // name, which game_events never carries - so a substitute who comes on
    // is nameable only from his 'substitutes' row.
    private static final String LINEUP_SQL = """
        SELECT club_id, player_id, player_name, position, type
        FROM game_lineups
        WHERE game_id = ?
        ORDER BY club_id, player_id
        """;


    // Half of the extra-time evidence. ADR 0009 pinned this as the whole
    // of it, on the reasoning that a quiet extra time leaves no event
    // trace - true of 28 matches, while appearances is missing entirely
    // for 1,174 that plainly played it, national-team football included.
    private static final String LENGTH_SQL = """
        SELECT max(minutes_played)
        FROM appearances
        WHERE game_id = ?
        """;

    // One match's events on the playing clock (ADR 0009): nominal, so the
    // whistle is at 90 minutes, or 120 where extra time was played.
    public List<MatchEvent> loadEvents(Match match) throws SQLException, UnusableMatchException {
        Map<Long, List<Player>> startersByClub = new HashMap<>();
        Map<Long, Player> goalkeeperByClub = new HashMap<>();
        Map<Long, Player> names = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(LINEUP_SQL)) {
            statement.setLong(1, match.matchId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                                        long clubId = rows.getLong("club_id");
                    Player player = new Player(
                        rows.getLong("player_id"), rows.getString("player_name"));
                    names.put(player.id(), player);
                    if (!"starting_lineup".equals(rows.getString("type"))) {
                        continue;   // on the bench: a name, not a starter
                    }
                    startersByClub.computeIfAbsent(clubId, key -> new ArrayList<>()).add(player);
                    if ("Goalkeeper".equals(rows.getString("position"))
                        && goalkeeperByClub.put(clubId, player) != null) {
                        throw new UnusableMatchException(
                            "two starting goalkeepers for club " + clubId);
                    }
                }
            }
        }
        if (startersByClub.isEmpty()) {
            throw new UnusableMatchException("no lineups");
        }

        List<MatchEvent> events = new ArrayList<>();
        events.add(startingXi(match, match.home(), startersByClub, goalkeeperByClub));
        events.add(startingXi(match, match.away(), startersByClub, goalkeeperByClub));
        readEvents(match, names, events);
        boolean extraTime = playedExtraTime(match.matchId(), events);
        MatchEvent.MatchEnd whistle = extraTime
            ? new MatchEvent.MatchEnd(4, 120, 0)
            : new MatchEvent.MatchEnd(2, 90, 0);

        // Tripwire: the whistle must be the last thing that happens. An
        // event beyond it means the length verdict and the stamps
        // contradict each other, and a replay of it would silently
        // accrue time after the match ended.
        int whistleTime = whistle.minute() * 60 + whistle.second();
        for (MatchEvent event : events) {
            int t = event.minute() * 60 + event.second();
            if (t > whistleTime) {
                throw new UnusableMatchException("an event at " + t + "s but the whistle at "
                    + whistleTime + "s - the clock would lie");
            }
        }
        events.add(whistle);
        EventOrdering.sort(events);
        return events;

    }

    // Extra time on either signal, because neither alone suffices: the
    // events miss a quiet extra time (28 matches), and appearances misses
    // whole competitions (1,174). Checked in that order because the events
    // are already in memory and settle most matches without a query.
    private boolean playedExtraTime(long matchId, List<MatchEvent> events) throws SQLException {
        for (MatchEvent event : events) {
            if (event.minute() >= 90) {
                return true;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(LENGTH_SQL)) {
            statement.setLong(1, matchId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) >= 120;
            }
        }
    }

    
    // Shootout rows are excluded by the glossary's Goal; minute -1 is the
    // vendor's "we cannot place this" stamp on 17,575 rows - and it is the
    // only non-positive minute in the database, so nothing legitimate is
    // lost by the filter.
    private static final String EVENT_SQL = """
        SELECT minute, type, club_id, player_id, player_in_id, description
        FROM game_events
        WHERE game_id = ? AND type <> 'Shootout' AND minute > 0
        ORDER BY minute
        """;

    // Per-event interpretation - the domain judgement SQL must not do.
    // A row the loader cannot place costs that event, never the match's
    // other thousand player-minutes.
    private void readEvents(Match match, Map<Long, Player> names, List<MatchEvent> events)
        throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(EVENT_SQL)) {
            statement.setString(1, String.valueOf(match.matchId()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int minute = rows.getInt("minute");
                    int t = (minute - 1) * 60 + 30;   // the labelled minute's midpoint
                    int period = periodOf(minute);
                    Team team = teamOf(match, rows.getLong("club_id"));
                    Player player = names.get(rows.getLong("player_id"));
                    String description = rows.getString("description");
                    if (team == null) {
                        droppedEvents++;   // credited to neither side of its own match
                        continue;
                    }
                    switch (rows.getString("type")) {
                        case "Goals" -> events.add(new MatchEvent.Goal(period, t / 60, t % 60,
                            team, isOwnGoal(description) ? null : player));
                        case "Substitutions" -> {
                            Player arriving = names.get(rows.getLong("player_in_id"));
                            if (player == null || arriving == null) {
                                droppedEvents++;   // nobody arriving: 612 games
                                continue;
                            }
                            events.add(new MatchEvent.Substitution(period, t / 60, t % 60,
                                team, player, arriving));
                        }
                        case "Cards" -> {
                            if (player != null && isSendingOff(description)) {
                                events.add(new MatchEvent.RedCard(period, t / 60, t % 60,
                                    team, player));
                            }
                        }
                        default -> droppedEvents++;
                    }
                }
            }
        }
    }

    // Nominal halves (ADR 0009): 45, 45, then two of 15. The minute is the
    // vendor's clamped label - minute 45 carries all first-half stoppage,
    // minute 90 all second-half stoppage - so these bounds are exact.
    private static int periodOf(int minute) {
        if (minute <= 45) {
            return 1;
        }
        if (minute <= 90) {
            return 2;
        }
        if (minute <= 105) {
            return 3;
        }
        return 4;
    }

    private static Team teamOf(Match match, long clubId) {
        if (clubId == match.home().id()) {
            return match.home();
        }
        if (clubId == match.away().id()) {
            return match.away();
        }
        return null;
    }

    // On an own goal the vendor's club_id is already the beneficiary, so
    // ownness changes nothing about who is credited - only that the scorer
    // is not one of the credited side's players, and so goes unnamed on
    // the event, exactly as in the StatsBomb loader.
    static boolean isOwnGoal(String description) {
        return description != null && description.contains("Own-goal");
    }

    // A card ends a match only if the description's first comma-segment,
    // stripped of its leading counter, is a red or a second yellow.
    // Trap: "3. Yellow card" is a player's third booking OF THE SEASON,
    // not a second yellow in this match.
    static boolean isSendingOff(String description) {
        if (description == null) {
            return false;
        }
        String card = description.split(",")[0].trim().replaceFirst("^\\d+\\.\\s*", "");
        return card.equals("Red card") || card.equals("Second yellow");
    }


    // The gate, per team (ADR 0009): a replay needs an eleven and a
    // goalkeeper, and a match missing either is dropped whole rather than
    // replayed wrong. The home flag is the loader's verdict, never the
    // fixture's label - NEITHER flags nobody.
    private static MatchEvent.StartingXI startingXi(Match match, Team team,
        Map<Long, List<Player>> startersByClub, Map<Long, Player> goalkeeperByClub)
        throws UnusableMatchException {

        List<Player> starters = startersByClub.get(team.id());
        if (starters == null || starters.size() != 11) {
            throw new UnusableMatchException("starting lineup of " + team.name() + " has "
                + (starters == null ? 0 : starters.size()) + " players, not 11");
        }
        Player goalkeeper = goalkeeperByClub.get(team.id());
        if (goalkeeper == null) {
            throw new UnusableMatchException("no starting goalkeeper for " + team.name());
        }
        boolean home = switch (match.homeSide()) {
            case HOME -> team.id() == match.home().id();
            case AWAY -> team.id() == match.away().id();
            case NEITHER -> false;
        };
        return new MatchEvent.StartingXI(1, 0, 0, team, starters, goalkeeper, home);
    }

    // ADR 0009: who, if anyone, is genuinely at home - three forks, not
    // two. In a national-team finals tournament the whole competition sits
    // at a chosen host, so the fixture label cannot mean what it says:
    // only a side of the host country is at home, and the club finals rule
    // never runs. The 2024 Asian Cup final was Qatar's genuine home match
    // at Lusail however the label reads. In club football the label does
    // name a real home fixture - domestic league, cup tie and European tie
    // alike - and only a chosen ground overturns it.
    static Match.HomeSide classifyHomeSide(Fixture fixture) {
        if ("national_team_competition".equals(fixture.competitionType())) {
            Long host = TOURNAMENT_HOSTS.get(
                new Edition(fixture.competitionId(), fixture.season()));
            if (host == null) {
                return Match.HomeSide.NEITHER;   // uncurated edition: no evidence, no advantage
            }
            if (host == fixture.homeClubId()) {
                return Match.HomeSide.HOME;
            }
            return host == fixture.awayClubId()
                ? Match.HomeSide.AWAY
                : Match.HomeSide.NEITHER;        // the host is not playing
        }
        if (NEUTRAL_ROUNDS.contains(
            new NeutralRound(fixture.competitionId(), fixture.round()))) {
            return Match.HomeSide.NEITHER;
        }
        if (SINGLE_MATCH_FINAL.equals(fixture.round())) {
            return Match.HomeSide.NEITHER;
        }
        return Match.HomeSide.HOME;
    }

}
