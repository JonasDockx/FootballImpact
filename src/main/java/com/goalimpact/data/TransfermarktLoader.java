package com.goalimpact.data;

import com.goalimpact.model.Competition;
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
import java.time.LocalDate;
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

    // Curated source facts (ADR 0009) again: the competitions that ARE league
    // football and that games.competition_type does not say so for. Item 16
    // reads that column to decide which clubs the run ever prices, so a
    // misfiled league is not cosmetic - it would hand every one of its clubs
    // the unpriced seed.
    //
    // Two families, both verified against tm.competitions on 2026-08-01:
    //
    //   COL1 - the Colombian first division, 200 matches with full lineups.
    //     It is one of five competitions the item 15 scraper added without a
    //     competitions row at all (with CGB, POCP, KLUB and UKRS), so its
    //     games carry competition_type NULL. Its 20 clubs appear nowhere else
    //     in the snapshot, so left alone every one of them reads as a cup
    //     minnow - which is exactly wrong: they are an ISLAND (item 9), a
    //     pool the run sees plenty of and cannot place, not a pool it barely
    //     sees at all.
    //   EJPL / POBE / BPO4 - the Belgian championship, Europe and relegation
    //     play-offs, filed by the vendor as type "other", sub_type "play_off".
    //     They are the second half of the Jupiler Pro League season, played by
    //     the same clubs. Listed for correctness rather than for consequence:
    //     those clubs already play BE1, so nothing about pricing changes. The
    //     home-advantage league anchor gains their 72 matches.
    //
    // The other four unnamed competitions need no entry - CGB is the English
    // League Cup, POCP the Taça de Portugal, KLUB the Club World Cup and UKRS
    // the Ukrainian Super Cup, and OTHER is already the right answer for all
    // four.
    private static final Set<String> LEAGUE_COMPETITIONS =
        Set.of("COL1", "EJPL", "POBE", "BPO4");


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
    private final boolean hasRegister;
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
    
    // The certain-tier worklist (item 25): every player named in a Held match,
    // recorded as the gate throws. Reusing the one gate means this list and the
    // skip report are one decision recorded twice, so they cannot drift.
    private final List<HeldAppearance> heldAppearances = new ArrayList<>();

    public List<HeldAppearance> heldAppearances() {
        return heldAppearances;
    }

    // The match-level spine (item 29, slice 1): every Held match, whatever the
    // reason. The two lists above and below it are conditional - one keyed on
    // the team-sheet reasons, one on "no lineups" - and a third family, "an
    // event outlives the whistle", falls through both and was recorded nowhere
    // at all. This one is unconditional by design: a match nobody names is
    // exactly the match the per-player worklist cannot reach.
    private record HeldRow(long gameId, long homeClubId, long awayClubId, String reason) {
    }

    private final List<HeldRow> heldRows = new ArrayList<>();

    // The Repair source is settled here rather than at the gate, because three
    // of its four rungs are set-shaped questions - does this game have
    // appearances, does it have events - and set-shaped work belongs in SQL
    // (ADR 0009), like appearedPlayers() beside it. The gate settles only the
    // rung it alone can see: a reason other than "no lineups" means a team sheet
    // exists and is merely broken.
    //
    // The appearances half reuses appearedGameIds(), the very set the appeared
    // tier is built from, so a match's source and its tier cannot disagree.
    public List<HeldMatch> heldMatches() throws SQLException {
        Set<Long> withAppearances = appearedGameIds();
        Set<Long> withEvents = eventGameIds();
        List<HeldMatch> out = new ArrayList<>(heldRows.size());
        for (HeldRow row : heldRows) {
            out.add(new HeldMatch(row.gameId(), row.homeClubId(), row.awayClubId(),
                row.reason(), sourceFor(row, withAppearances, withEvents)));
        }
        return out;
    }

    private static RepairSource sourceFor(HeldRow row,
        Set<Long> withAppearances, Set<Long> withEvents) {

        if (!row.reason().equals("no lineups")) {
            return RepairSource.TEAM_SHEET;
        }
        if (withAppearances.contains(row.gameId())) {
            return RepairSource.APPEARANCES;
        }
        return withEvents.contains(row.gameId()) ? RepairSource.EVENTS : RepairSource.NOTHING;
    }

    // The maybe-tier match set (item 26, stage 4a): every "no lineups" Held
    // match, recorded as the gate throws it - so this set IS the gate's verdict,
    // never a second SQL definition of "no lineups" that could drift from the
    // skip report. A "no lineups" match names nobody (that is what it means), so
    // unlike heldAppearances there are no player rows to keep here; the names are
    // joined on afterwards from appearances (appearedPlayers / maybePlayers).
    // Released matches never throw, so they are excluded for free.
    private record NoLineupMatch(long gameId, long homeClubId, long awayClubId,
        LocalDate date) {
    }

    private final List<NoLineupMatch> heldNoLineup = new ArrayList<>();

    public int heldNoLineupCount() {
        return heldNoLineup.size();
    }

    // The appeared tier (item 26, stage 4a): every player the vendor's
    // appearances record names in a "no lineups" Held match. The match set is
    // the gate's own (heldNoLineup), so appearances supplies only the names and
    // minutes - set-shaped work, so SQL (ADR 0009). A game with no appearances
    // contributes nothing here and falls to the maybe tier instead. The id list
    // is spliced, not bound: every value is a game id the loader itself minted.
    public List<AppearedPlayer> appearedPlayers() throws SQLException {
        List<AppearedPlayer> appeared = new ArrayList<>();
        if (heldNoLineup.isEmpty()) {
            return appeared;
        }
        String sql = "SELECT game_id, player_club_id, player_id, player_name, minutes_played"
            + " FROM appearances WHERE game_id IN (" + gameIdList() + ")"
            + " ORDER BY game_id, player_club_id, player_id";
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                appeared.add(new AppearedPlayer(
                    rows.getLong("game_id"),
                    rows.getLong("player_club_id"),
                    rows.getLong("player_id"),
                    rows.getString("player_name"),
                    rows.getInt("minutes_played")));
            }
        }
        return appeared;
    }

    // The maybe tier (item 26, stage 4a): candidates for a "no lineups" Held
    // match that carries no appearances either, so nobody is nameable at all -
    // drawn from the club's squad in the month around the date (glossary
    // 'Worklist tier'). nearby_matches counts how many nearby matches the player
    // turned out for, so the repair GUI can rank a regular above a long shot.
    // Only the games with no appearances reach here; a per-game (id, clubs, date)
    // table is spliced inline so each match's window uses its own date and clubs.
    public List<MaybePlayer> maybePlayers() throws SQLException {
        List<MaybePlayer> maybe = new ArrayList<>();
        Set<Long> appearedGames = appearedGameIds();
        StringBuilder values = new StringBuilder();
        for (NoLineupMatch m : heldNoLineup) {
            if (appearedGames.contains(m.gameId())) {
                continue;   // it has appearances: the appeared tier's, not maybe's
            }
            if (values.length() > 0) {
                values.append(',');
            }
            values.append('(').append(m.gameId()).append(',')
                .append(m.homeClubId()).append(',').append(m.awayClubId())
                .append(",DATE '").append(m.date()).append("')");
        }
        if (values.length() == 0) {
            return maybe;
        }
        String sql = "WITH nl(gid, home_club, away_club, gdate) AS (VALUES " + values + ")"
            + " SELECT nl.gid, a.player_club_id, a.player_id,"
            + " any_value(a.player_name) AS player_name, count(DISTINCT a.game_id) AS nearby"
            + " FROM nl JOIN appearances a"
            + "   ON (a.player_club_id = nl.home_club OR a.player_club_id = nl.away_club)"
            + "  AND a.date BETWEEN nl.gdate - INTERVAL 30 DAY AND nl.gdate + INTERVAL 30 DAY"
            + " GROUP BY nl.gid, a.player_club_id, a.player_id"
            + " ORDER BY nl.gid, a.player_club_id, a.player_id";
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                maybe.add(new MaybePlayer(
                    rows.getLong("gid"),
                    rows.getLong("player_club_id"),
                    rows.getLong("player_id"),
                    rows.getString("player_name"),
                    rows.getInt("nearby")));
            }
        }
        return maybe;
    }

    // Which of the no-lineup Held matches carry any appearances at all - the
    // line that splits the appeared tier from the maybe tier.
    private Set<Long> appearedGameIds() throws SQLException {
        Set<Long> ids = new HashSet<>();
        if (heldNoLineup.isEmpty()) {
            return ids;
        }
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT DISTINCT game_id FROM appearances WHERE game_id IN ("
                + gameIdList() + ")")) {
            while (rows.next()) {
                ids.add(rows.getLong("game_id"));
            }
        }
        return ids;
    }

    // Which no-lineup Held matches carry events of their own - the difference
    // between a match slice 2 can derive a lineup for and one with nothing left
    // to derive from (item 29). Only the no-lineup set is asked about: a match
    // that still has a team sheet is a TEAM_SHEET repair whatever its events say.
    private Set<Long> eventGameIds() throws SQLException {
        Set<Long> ids = new HashSet<>();
        if (heldNoLineup.isEmpty()) {
            return ids;
        }
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT DISTINCT game_id FROM game_events WHERE game_id IN ("
                + gameIdList() + ")")) {
            while (rows.next()) {
                ids.add(rows.getLong("game_id"));
            }
        }
        return ids;
    }

    // The no-lineup Held match ids as a comma-separated list for an IN clause.
    // Splicing is safe: these are ids the loader recorded from its own gate,
    // never user input.
    private String gameIdList() {
        StringBuilder ids = new StringBuilder();
        for (NoLineupMatch m : heldNoLineup) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(m.gameId());
        }
        return ids.toString();
    }

    // How many matches the sidecar releases into this run - announced by Main
    // so a released override never moves the census silently (ADR 0009: every
    // skip is counted and printed, and a release is the same principle).
    public int releasedCount() {
        return released.size();
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
        this.hasRegister = hasSidecar && sidecarHasRegister();
        this.released = hasSidecar ? loadReleasedIds() : Set.of();
    }

    // Whether the attached sidecar carries ADR 0012's register. Three states have
    // to survive: no sidecar, a pre-ADR-0012 file with four tables, and a current
    // one with five.
    private boolean sidecarHasRegister() throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(
                "SELECT 1 FROM duckdb_tables()"
                    + " WHERE database_name = 'sidecar' AND table_name = 'manual_players'")) {
            return rows.next();
        }
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

    // Every date of birth the snapshot knows, for ADR 0016's ageing curve.
    //
    // ADR 0011 said this needed "no date-of-birth loading in Java at all", and
    // that was true of the CHART: the index is computed at reporting time,
    // where SQL can reach players.date_of_birth itself. ADR 0016 moves the age
    // term INSIDE the replay, so every man on the pitch now needs an age while
    // the match is being played - not just the 17,030 the chart draws. That is
    // what changed, and it is why this method exists.
    //
    // Read whole rather than per match: 95k rows of (id, date) is a few MiB
    // against a replay that already holds the fixture list, and the alternative
    // is a query per match. Players with no row and players whose row carries
    // no date are simply absent - AgeingCurve charges them the
    // population-average penalty, which is what an unknown age is worth.
    //
    // ADR 0012 decision 7 again: the register is authoritative for a
    // hand-typed player, here too. It is read second so it wins, and it is the
    // only source for a created player, who appears in no vendor table at all.
    public Map<Long, LocalDate> birthDates() throws SQLException {
        Map<Long, LocalDate> born = new HashMap<>();
        readBirthDatesInto(
            "SELECT player_id, TRY_CAST(date_of_birth AS DATE) AS dob FROM players", born);
        if (hasRegister) {
            readBirthDatesInto("SELECT player_id, CAST(date_of_birth AS DATE) AS dob"
                + " FROM sidecar.manual_players", born);
        }
        return born;
    }

    private void readBirthDatesInto(String sql, Map<Long, LocalDate> into) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                java.sql.Date dob = rows.getDate("dob");
                if (dob != null) {
                    into.put(rows.getLong("player_id"), dob.toLocalDate());
                }
            }
        }
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
        return new Match(
            matchId,
            classifyCompetition(fixture),
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
            ResultSet rows = statement.executeQuery(SIDECAR_LINEUP_SQL.formatted(
                hasRegister ? "mp.player_name," : "",
                hasRegister
                    ? "LEFT JOIN sidecar.manual_players mp ON mp.player_id = l.player_id"
                    : ""))) {
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
    // ADR 0012 decision 7 (item 17, slice 2): the register is authoritative for a
    // hand-typed name, at replay time too. A released match is a snapshot that
    // keeps whatever name it was saved with, so without this join a man named by
    // hand today would still read as "player 117799" in the games he is already
    // in - and naming him once is precisely what was asked for.
    //
    // Only the sidecar's lineups are joined, and that is the whole population by
    // construction: a created player appears in no vendor table at all, and a
    // nameless vendor id appears in no lineup anywhere until a repair puts him in
    // one. The register may be absent - a file written before ADR 0012 has four
    // tables - and naming a missing table is a hard error in DuckDB, so the join
    // is formatted in only when the table is really there.
    private static final String SIDECAR_LINEUP_SQL = """
        SELECT l.game_id AS gid, l.club_id, l.player_id,
               COALESCE(%s l.player_name) AS player_name, l.position, l.type
        FROM sidecar.game_lineups l
        %s
        ORDER BY gid, l.club_id, l.player_id
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

    // The Held reasons whose matches HAVE a starting XI, so their players are
    // nameable (item 25, certain tier). "no lineups" names nobody (that career
    // is the maybe tier's job), and the post-whistle tripwire fires on an
    // otherwise-valid match - neither belongs here.
    private static final Set<String> HELD_REASONS = Set.of(
        "XI is not 11", "no starting goalkeeper", "two starting goalkeepers");

    // One match's events on the playing clock (ADR 0009): nominal, so the
    // whistle is at 90 minutes, or 120 where extra time was played.
    public List<MatchEvent> loadEvents(Match match) throws UnusableMatchException {
        // All three taken up front, and removed rather than read: a match
        // that fails the gate below must not leave its rows behind.
        List<LineupRow> lineup = stagedLineups.remove(match.matchId());
        Integer longest = stagedLength.remove(match.matchId());
        List<EventRow> eventRows = stagedEvents.remove(match.matchId());
        Integer lastMinute = stagedLastMinute.remove(match.matchId());

        try {
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
        } catch (UnusableMatchException e) {
            // The same throw that counts the skip fills the worklist. The
            // match-level row comes first and is unconditional (item 29): the
            // player rows below are what a per-player worklist can reach, and
            // this is what a club-scoped one can.
            heldRows.add(new HeldRow(match.matchId(),
                match.home().id(), match.away().id(), e.reason()));

            // A Held match with a starting XI names its players (starters and
            // bench); the raw lineup rows are still in hand because they were
            // removed up front, before the gate ran.
            if (HELD_REASONS.contains(e.reason()) && lineup != null) {
                for (LineupRow row : lineup) {
                    heldAppearances.add(new HeldAppearance(match.matchId(),
                        row.clubId(), row.playerId(), row.playerName(),
                        row.starter(), e.reason()));
                }
            } else if (e.reason().equals("no lineups")) {
                // No team sheet, so nobody is nameable here; record the match
                // (id, both clubs, date) for the appeared/maybe join to fill in.
                heldNoLineup.add(new NoLineupMatch(match.matchId(),
                    match.home().id(), match.away().id(), match.date()));
            }
            throw e;
        }
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

    // Item 16: the vendor's competition_type, corrected, and mapped into the
    // engine's three kinds (ADR 0004 - "domestic_league" is Transfermarkt's
    // word, and it stops at this boundary). The curated set wins over the
    // column because five competitions have no competitions row at all and so
    // carry no type, and because a misfiled league is the one error that
    // changes an answer here.
    static Competition classifyCompetition(Fixture fixture) {
        if (LEAGUE_COMPETITIONS.contains(fixture.competitionId())
            || "domestic_league".equals(fixture.competitionType())) {
            return new Competition(fixture.competitionId(), Competition.Kind.LEAGUE);
        }
        if ("national_team_competition".equals(fixture.competitionType())) {
            return new Competition(fixture.competitionId(), Competition.Kind.NATIONAL_TEAM);
        }
        return new Competition(fixture.competitionId(), Competition.Kind.OTHER);
    }

}
