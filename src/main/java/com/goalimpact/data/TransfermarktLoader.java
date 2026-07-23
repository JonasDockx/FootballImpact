package com.goalimpact.data;

import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    // Competitions that are never anyone's home fixture, whatever the
    // label or the round name says. The Club World Cup is a tournament at
    // a chosen host - Japan, Morocco, the UAE, Qatar, and 63 games in the
    // USA in 2025 - and the Ukrainian Super Cup is a one-off at a neutral
    // ground, like the Community Shield.
    //
    // Scoped to the whole competition rather than to its rounds because
    // that is what the fact is. UKRS proves the point: nine of its ten
    // rows are named "Final" and the tenth "final decider", which a
    // round-scoped rule would have missed.
    //
    // The Club World Cup's host is deliberately NOT resolved. Most
    // entrants - Auckland City, Raja, Al-Ain - play in leagues this
    // snapshot does not carry, so clubs.domestic_competition_id cannot
    // name their country. No evidence, no advantage: the same posture
    // classifyHomeSide already takes for an uncurated tournament edition.
    private static final Set<String> NEUTRAL_COMPETITIONS = Set.of("KLUB", "UKRS");


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
    // has played its semi-finals at Wembley since 2008, and the Spanish
    // and Italian super cups have played theirs in Saudi Arabia since
    // 2020, while the DFB-Pokal, KNVB Beker and Russian Cup play theirs
    // at a club's own ground - a blanket rule on the round name would be
    // wrong in eleven competitions to be right in three.
    private static final Set<NeutralRound> NEUTRAL_ROUNDS = Set.of(
        new NeutralRound("FAC", "Semi-Finals"),
        new NeutralRound("SUC", "Semi-Finals"),
        new NeutralRound("SCI", "Semi-Finals"));


    private final Connection connection;
    private long droppedEvents = 0; // events the source could not place

    // The sidecar (ADR 0009, item 26). Attached read-only beside the vendor
    // snapshot when the file exists; absent, everything below is inert and
    // the loader is exactly the vendor path. A RELEASED sidecar match wins
    // over the vendor's copy of that game id outright - a whole-match
    // replacement - while a draft is invisible and rates nowhere.
    private final boolean hasSidecar;
    private final Set<Long> released;       // game ids the sidecar has released
    private boolean sidecarStaged = false;  // stageSidecar runs once, not per slice

    // Batched vendor rows, keyed by game id and drained as loadEvents
    // consumes them. Staging ACCUMULATES across loadMatches calls, so Main
    // can load every slice and only then replay; it drains as replays are
    // built, so staging and replays are never both held whole.
    private final Map<Long, List<LineupRow>> stagedLineups = new HashMap<>();
    private final Map<Long, Integer> stagedLength = new HashMap<>();
    private final Map<Long, List<EventRow>> stagedEvents = new HashMap<>();
    private final Map<Long, Integer> stagedLastMinute = new HashMap<>();
    
    // Which loaded matches are domestic league fixtures. Source-specific
    // knowledge - "domestic_league" is Transfermarkt's word - so it lives
    // here rather than on Match, which the engine and the StatsBomb loader
    // also share (ADR 0004).
    private final Set<Long> leagueMatches = new HashSet<>();

    public Set<Long> leagueMatches() {
        return leagueMatches;
    }

    // One game_lineups row, with the two string tests already decided.
    // Deciding them here is the same Java judgement run when the row is
    // read rather than when the match is assembled - it never moves into
    // SQL - and it keeps 3.18M position/type strings out of the heap.
    private record LineupRow(long clubId, long playerId, String playerName,
        boolean starter, boolean goalkeeper) {
    }

    // One game_events row, with description already reduced to the two
    // questions the loader ever asks of it. 1.26M description strings
    // never reach the heap, and the rules that read them - isOwnGoal,
    // isSendingOff - are unchanged and still in Java.
    private record EventRow(int minute, String type, long clubId,
        long playerId, long playerInId, boolean ownGoal, boolean sendingOff) {
    }

    // ADR 0009: everything skipped is counted, never printed and forgotten
    public long droppedEvents() {
        return droppedEvents;
    }

    public TransfermarktLoader(Path snapshot) throws SQLException {
        this(snapshot, null);
    }

    // The sidecar is optional and read-only. A null or absent file leaves
    // hasSidecar false, so released is empty and every sidecar branch below
    // is skipped - the byte-identical inert stage (item 26, stage 1).
    public TransfermarktLoader(Path snapshot, Path sidecar) throws SQLException {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        this.connection = DriverManager.getConnection("jdbc:duckdb:" + snapshot, readOnly);
        this.hasSidecar = sidecar != null && Files.exists(sidecar);
        if (hasSidecar) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH '"
                    + sidecar.toString().replace('\\', '/') + "' AS sidecar (READ_ONLY)");
            }
        }
        this.released = hasSidecar ? loadReleasedIds() : Set.of();
    }

    // The released game ids, read once at construction. Draft rows are
    // excluded here, so a draft never enters the override and rates nowhere.
    private Set<Long> loadReleasedIds() throws SQLException {
        Set<Long> ids = new HashSet<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT game_id FROM sidecar.matches WHERE status = 'released'")) {
            while (rows.next()) {
                ids.add(rows.getLong("game_id"));
            }
        }
        return ids;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    // The whole spine in one query. 630 competition-seasons exist, so the
    // enumerated slice list was never going to reach them; the slice form
    // below stays for the pinned regression runs and for reading one
    // league by hand.
    public List<Match> loadMatches() throws SQLException {
        return loadMatches(null, null);
    }

    public List<Match> loadMatches(String competitionId, String season) throws SQLException {
        String sql = """
                SELECT game_id, date, competition_id, season, round, competition_type,
                home_club_id, home_club_name,
                away_club_id, away_club_name,
                home_club_goals, away_club_goals

                FROM games
                %s
                ORDER BY date, CAST(game_id AS BIGINT)
                """;
        List<Match> matches = new ArrayList<>();
        try (PreparedStatement statement = prepare(sql,
            "WHERE competition_id = ? AND season = ?", competitionId, season)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    // The fixture describes the ROW, never the arguments -
                    // under loadMatches() they are null and the home-side
                    // rule reads the row's own competition id, season, round.
                    long matchId = Long.parseLong(rows.getString("game_id"));
                    if (released.contains(matchId)) {
                        continue;   // the sidecar's released copy wins; skip the vendor's
                    }
                    matches.add(buildMatch(rows, matchId));
                }
            }
        }
        if (hasSidecar) {
            try (PreparedStatement statement = prepare(SIDECAR_MATCH_SQL,
                "AND competition_id = ? AND season = ?", competitionId, season)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        matches.add(buildMatch(rows, rows.getLong("game_id")));
                    }
                }
            }
        }
        stage(competitionId, season);
        return matches;
    }

    // The Fixture and the Match from one games/sidecar.matches row. Shared
    // so the vendor row and the sidecar row are classified identically; the
    // only difference between the two callers is how game_id is read (the
    // vendor stores it VARCHAR, the sidecar BIGINT).
    private Match buildMatch(ResultSet rows, long matchId) throws SQLException {
        Fixture fixture = new Fixture(
            rows.getString("competition_id"), rows.getString("season"),
            rows.getString("competition_type"), rows.getString("round"),
            rows.getLong("home_club_id"), rows.getLong("away_club_id"));
        if ("domestic_league".equals(fixture.competitionType())) {
            leagueMatches.add(matchId);
        }
        return new Match(
            matchId,
            rows.getDate("date").toLocalDate(),
            new Team(rows.getLong("home_club_id"), rows.getString("home_club_name")),
            new Team(rows.getLong("away_club_id"), rows.getString("away_club_name")),
            rows.getInt("home_club_goals"),
            rows.getInt("away_club_goals"),
            classifyHomeSide(fixture));
    }


    // One edition, or all of them. The filter is spliced rather than bound
    // as a nullable parameter, because a NULL parameter has to carry its
    // own type through JDBC and nothing here is ever user input. Splicing
    // nothing is what turns 630 queries into one.
    private PreparedStatement prepare(String sql, String filter,
        String competitionId, String season) throws SQLException {

        if (competitionId == null) {
            return connection.prepareStatement(sql.formatted(""));
        }
        PreparedStatement statement = connection.prepareStatement(sql.formatted(filter));
        statement.setString(1, competitionId);
        statement.setString(2, season);
        return statement;
    }


    // The set-shaped half of the loader (ADR 0009): two queries fetch every
    // row this predicate's matches will need, and Java keeps every judgement
    // about what those rows mean. The batch unit is deliberately the same
    // predicate loadMatches was just given, so a slice run stages a slice
    // and the full run stages everything - and no caller has to remember to
    // prefetch before loadEvents will work.
    private void stage(String competitionId, String season) throws SQLException {
        try (PreparedStatement statement = prepare(LINEUP_SQL,
            "WHERE g.competition_id = ? AND g.season = ?", competitionId, season)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long gid = rows.getLong("gid");
                    if (released.contains(gid)) {
                        continue;   // this match's lineup comes from the sidecar
                    }
                    stagedLineups.computeIfAbsent(gid, key -> new ArrayList<>())
                        .add(new LineupRow(
                            rows.getLong("club_id"),
                            rows.getLong("player_id"),
                            rows.getString("player_name"),
                            "starting_lineup".equals(rows.getString("type")),
                            "Goalkeeper".equals(rows.getString("position"))));
                }
            }
        }
        try (PreparedStatement statement = prepare(LENGTH_SQL,
            "WHERE g.competition_id = ? AND g.season = ?", competitionId, season)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long gid = rows.getLong("gid");
                    if (released.contains(gid)) {
                        continue;
                    }
                    stagedLength.put(gid, rows.getInt("longest"));
                }
            }
        }
        try (PreparedStatement statement = prepare(EVENT_SQL,
            "AND g.competition_id = ? AND g.season = ?", competitionId, season)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long gid = rows.getLong("gid");
                    if (released.contains(gid)) {
                        continue;
                    }
                    int minute = rows.getInt("minute");
                    // The latest minute the SOURCE stamps on this match,
                    // recorded before any row is judged or discarded.
                    stagedLastMinute.merge(gid, minute, Math::max);
                    String description = rows.getString("description");
                    stagedEvents.computeIfAbsent(gid, key -> new ArrayList<>())
                        .add(new EventRow(
                            minute,
                            rows.getString("type"),
                            rows.getLong("club_id"),
                            rows.getLong("player_id"),
                            rows.getLong("player_in_id"),
                            isOwnGoal(description),
                            isSendingOff(description)));
                }
            }
        }
        if (hasSidecar && !sidecarStaged) {
            stageSidecar();
            sidecarStaged = true;
        }
    }

        // The sidecar overlay: the released matches' rows, keyed by game id into
    // the same staging maps the vendor filled. Predicate-free and run once
    // (sidecarStaged), because releases are few and independent of any slice.
    // Draft rows are skipped in Java via released, mirroring loadReleasedIds.
    private void stageSidecar() throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(SIDECAR_LINEUP_SQL)) {
            while (rows.next()) {
                long gid = rows.getLong("gid");
                if (!released.contains(gid)) {
                    continue;   // a draft's rows never rate
                }
                stagedLineups.computeIfAbsent(gid, key -> new ArrayList<>())
                    .add(new LineupRow(
                        rows.getLong("club_id"),
                        rows.getLong("player_id"),
                        rows.getString("player_name"),
                        "starting_lineup".equals(rows.getString("type")),
                        "Goalkeeper".equals(rows.getString("position"))));
            }
        }
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(SIDECAR_EVENT_SQL)) {
            while (rows.next()) {
                long gid = rows.getLong("gid");
                if (!released.contains(gid)) {
                    continue;
                }
                int minute = rows.getInt("minute");
                stagedLastMinute.merge(gid, minute, Math::max);
                String description = rows.getString("description");
                stagedEvents.computeIfAbsent(gid, key -> new ArrayList<>())
                    .add(new EventRow(
                        minute,
                        rows.getString("type"),
                        rows.getLong("club_id"),
                        rows.getLong("player_id"),
                        rows.getLong("player_in_id"),
                        isOwnGoal(description),
                        isSendingOff(description)));
            }
        }
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(SIDECAR_LENGTH_SQL)) {
            while (rows.next()) {
                long gid = rows.getLong("gid");
                if (!released.contains(gid)) {
                    continue;
                }
                stagedLength.put(gid, rows.getInt("longest"));
            }
        }
    }

    // The sidecar's rows go through the very same Java judgement as the
    // vendor's - the same LineupRow/EventRow mapping, the same clock and
    // ordering in loadEvents - so a released match is not a special replay,
    // just a different source for the same shape. The event filter matches
    // the vendor's: no shootout rows, no minute <= 0.
    private static final String SIDECAR_LINEUP_SQL = """
        SELECT game_id AS gid, club_id, player_id, player_name, position, type
        FROM sidecar.game_lineups
        ORDER BY gid, club_id, player_id
        """;

    private static final String SIDECAR_EVENT_SQL = """
        SELECT game_id AS gid, minute, type, club_id, player_id, player_in_id, description
        FROM sidecar.game_events
        WHERE minute > 0 AND type <> 'Shootout'
        ORDER BY gid, minute
        """;

    private static final String SIDECAR_LENGTH_SQL = """
        SELECT game_id AS gid, max(minutes_played) AS longest
        FROM sidecar.appearances
        GROUP BY gid
        """;

    // Both lineup types: the starters form the XI, and every row supplies a
    // name, which game_events never carries - so a substitute who comes on
    // is nameable only from his 'substitutes' row.
    //
    // The ORDER BY is load-bearing. It fixes the order of the starters list
    // inside StartingXI, so it must stay exactly what the per-match query
    // used - club_id then player_id - now preceded by the game id.
    private static final String LINEUP_SQL = """
        SELECT CAST(l.game_id AS BIGINT) AS gid,
               l.club_id, l.player_id, l.player_name, l.position, l.type
        FROM game_lineups l
        JOIN games g ON CAST(g.game_id AS BIGINT) = CAST(l.game_id AS BIGINT)
        %s
        ORDER BY gid, l.club_id, l.player_id
        """;

    // Half of the extra-time evidence. ADR 0009 pinned this as the whole
    // of it, on the reasoning that a quiet extra time leaves no event
    // trace - true of 28 matches, while appearances is missing entirely
    // for 1,174 that plainly played it, national-team football included.
    //
    // appearances.game_id is INTEGER where games.game_id is VARCHAR: the
    // cast goes on the games side, so the join can still use the integer.
    private static final String LENGTH_SQL = """
        SELECT a.game_id AS gid, max(a.minutes_played) AS longest
        FROM appearances a
        JOIN games g ON CAST(g.game_id AS BIGINT) = a.game_id
        %s
        GROUP BY gid
        """;

    // The sidecar's released matches (item 26). Same columns loadMatches
    // reads from games, so buildMatch consumes either. game_id is BIGINT
    // here, so it needs no cast. The %s takes the same optional slice
    // predicate the vendor query uses, via prepare().
    private static final String SIDECAR_MATCH_SQL = """
        SELECT game_id, date, competition_id, season, round, competition_type,
               home_club_id, home_club_name, away_club_id, away_club_name,
               home_club_goals, away_club_goals
        FROM sidecar.matches
        WHERE status = 'released' %s
        ORDER BY date, game_id
        """;

    // One match's events on the playing clock (ADR 0009): nominal, so the
    // whistle is at 90 minutes, or 120 where extra time was played.
    public List<MatchEvent> loadEvents(Match match) throws UnusableMatchException {
        // All three taken up front, and removed rather than read: a match
        // that fails the gate below must not leave its rows behind.
        List<LineupRow> lineup = stagedLineups.remove(match.matchId());
        Integer longest = stagedLength.remove(match.matchId());
        List<EventRow> eventRows = stagedEvents.remove(match.matchId());
        Integer lastMinute = stagedLastMinute.remove(match.matchId());

        Map<Long, List<Player>> startersByClub = new HashMap<>();
        Map<Long, Player> goalkeeperByClub = new HashMap<>();
        Map<Long, Player> names = new HashMap<>();
        if (lineup != null) {
            for (LineupRow row : lineup) {
                Player player = new Player(row.playerId(), row.playerName());
                names.put(player.id(), player);
                if (!row.starter()) {
                    continue;   // on the bench: a name, not a starter
                }
                startersByClub.computeIfAbsent(row.clubId(), key -> new ArrayList<>()).add(player);
                if (row.goalkeeper() && goalkeeperByClub.put(row.clubId(), player) != null) {
                    throw new UnusableMatchException("two starting goalkeepers",
                        "club " + row.clubId());
                }
            }
        }
        if (startersByClub.isEmpty()) {
            throw new UnusableMatchException("no lineups");
        }


        List<MatchEvent> events = new ArrayList<>();
        events.add(startingXi(match, match.home(), startersByClub, goalkeeperByClub));
        events.add(startingXi(match, match.away(), startersByClub, goalkeeperByClub));
        readEvents(match, eventRows, names, events);
        boolean extraTime = playedExtraTime(longest, lastMinute);
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
                throw new UnusableMatchException("an event outlives the whistle",
                    "event at " + t + "s, whistle at " + whistleTime + "s");
            }
        }
        events.add(whistle);
        EventOrdering.sort(events);
        return events;

    }

    // Extra time on either signal, because neither alone suffices: the
    // source misses a quiet extra time (28 matches), and appearances
    // misses whole competitions (1,174).
    //
    // lastMinute comes from the SOURCE ROWS, not from the events built
    // out of them. A row that never becomes an event - an ordinary yellow
    // card, a substitution whose arriving player has no name - still
    // proves the match was being played at that minute. Reading the built
    // events instead blew the whistle at 90 on 26 matches that played 120.
    private static boolean playedExtraTime(Integer longest, Integer lastMinute) {
        return (lastMinute != null && lastMinute > 90)
            || (longest != null && longest >= 120);
    }

    // Shootout rows are excluded by the glossary's Goal; minute -1 is the
    // vendor's "we cannot place this" stamp on 17,575 rows - and it is the
    // only non-positive minute in the database, so nothing legitimate is
    // lost by the filter.
    //
    // ORDER BY gid, minute reproduces the per-match ORDER BY minute. What
    // neither query fixes is the order of two events sharing a minute:
    // EventOrdering.sort ranks them and is stable, so same-rank ties keep
    // whatever order the scan produced.
    private static final String EVENT_SQL = """
        SELECT CAST(e.game_id AS BIGINT) AS gid,
               e.minute, e.type, e.club_id, e.player_id, e.player_in_id, e.description
        FROM game_events e
        JOIN games g ON CAST(g.game_id AS BIGINT) = CAST(e.game_id AS BIGINT)
        WHERE e.type <> 'Shootout' and e.minute > 0
        %s
        ORDER BY gid, e.minute
        """;

    // Per-event interpretation - the domain judgement SQL must not do.
    // A row the loader cannot place costs that event, never the match's
    // other thousand player-minutes.
    private void readEvents(Match match, List<EventRow> rows,
        Map<Long, Player> names, List<MatchEvent> events) {

        if (rows == null) {
            return;   // a match the source records no events for
        }
        for (EventRow row : rows) {
            int t = (row.minute() - 1) * 60 + 30;   // the labelled minute's midpoint
            int period = periodOf(row.minute());
            Team team = teamOf(match, row.clubId());
            Player player = names.get(row.playerId());
            if (team == null) {
                droppedEvents++;   // credited to neither side of its own match
                continue;
            }
            switch (row.type()) {
                case "Goals" -> events.add(new MatchEvent.Goal(period, t / 60, t % 60,
                    team, row.ownGoal() ? null : player));
                case "Substitutions" -> {
                    Player arriving = names.get(row.playerInId());
                    if (player == null || arriving == null) {
                        droppedEvents++;   // nobody arriving: 612 games
                        continue;
                    }
                    events.add(new MatchEvent.Substitution(period, t / 60, t % 60,
                        team, player, arriving));
                }
                case "Cards" -> {
                    if (player != null && row.sendingOff()) {
                        events.add(new MatchEvent.RedCard(period, t / 60, t % 60,
                            team, player));
                    }
                }
                default -> droppedEvents++;
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
            throw new UnusableMatchException("XI is not 11", team.name() + " has "
                + (starters == null ? 0 : starters.size()) + " players");
        }
        Player goalkeeper = goalkeeperByClub.get(team.id());
        if (goalkeeper == null) {
            throw new UnusableMatchException("no starting goalkeeper", team.name());
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
        if (NEUTRAL_COMPETITIONS.contains(fixture.competitionId())) {
            return Match.HomeSide.NEITHER;   // never anyone's ground
        }
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
