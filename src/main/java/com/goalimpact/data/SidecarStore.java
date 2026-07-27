package com.goalimpact.data;

import com.goalimpact.repair.AppearanceRow;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.EventRow;
import com.goalimpact.repair.LineupEntry;
import com.goalimpact.repair.ManualPlayer;
import com.goalimpact.repair.MatchHeader;
import com.goalimpact.repair.PlayerCandidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

// The sidecar's write path (item 26, stage 4b-2), and the first code in the
// project that ever writes it. It bridges the two worlds CLAUDE.md keeps apart:
// the repair package holds plain editable objects that know no SQL, and this
// class - in data, the only package that may - turns them into rows and back.
//
// The sidecar is opened per operation and closed at once (decision 6). DuckDB
// allows one writer, and Main reads the sidecar on every replay, so a
// session-long handle would make "close the GUI before replaying" a second hard
// rule you eventually forget; opening for milliseconds means the locking rules
// never have to be known.
//
// The four-table schema is the one first-repair.sql cemented at stage 3. It is
// written down twice on purpose (decision 3): the SQL script stays the record of
// stage 3, and this class becomes the live definition, creating the tables itself
// so opening a temp path yields an empty sidecar and every write test is a
// one-liner.
public final class SidecarStore {

    private final Path sidecar;
    private final Path snapshot;

    public SidecarStore(Path sidecar, Path snapshot) {
        this.sidecar = sidecar;
        this.snapshot = snapshot;
    }

    // The schema, verbatim from first-repair.sql but guarded so a repeat is a
    // no-op. "type" is quoted because it is a reserved word.
    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS matches (
            game_id BIGINT, status VARCHAR, date DATE, competition_id VARCHAR,
            season VARCHAR, round VARCHAR, competition_type VARCHAR,
            home_club_id BIGINT, home_club_name VARCHAR,
            away_club_id BIGINT, away_club_name VARCHAR,
            home_club_goals INTEGER, away_club_goals INTEGER,
            provenance VARCHAR, commit_hash VARCHAR)
        """,
        """
        CREATE TABLE IF NOT EXISTS game_lineups (
            game_id BIGINT, club_id BIGINT, player_id BIGINT,
            player_name VARCHAR, position VARCHAR, "type" VARCHAR)
        """,
        """
        CREATE TABLE IF NOT EXISTS game_events (
            game_id BIGINT, minute INTEGER, "type" VARCHAR, club_id BIGINT,
            player_id BIGINT, player_in_id BIGINT, description VARCHAR)
        """,
        """
        CREATE TABLE IF NOT EXISTS appearances (
            game_id BIGINT, minutes_played INTEGER)
        """,
        // The fifth table (ADR 0012), and the first sidecar state that is not a
        // whole match: the register of players named by hand. It is written inside
        // save's transaction, never on the create click, which buys the invariant
        // that every row here is a player who appears in at least one sidecar match.
        """
        CREATE TABLE IF NOT EXISTS manual_players (
            player_id BIGINT, player_name VARCHAR, date_of_birth DATE,
            created_on TIMESTAMP, note VARCHAR)
        """
    };

    // One read shape serves both files: the sidecar's matches/game_lineups/etc
    // and the vendor's games/game_lineups/etc share every column these SELECTs
    // name, so only the header table's name differs (matches vs games). Every
    // WHERE casts game_id, which is VARCHAR in games and game_events but numeric
    // elsewhere - the loader's documented trap.
    private static final String HEADER_SQL = """
        SELECT date, competition_id, season, round, competition_type,
               home_club_id, home_club_name, away_club_id, away_club_name,
               home_club_goals, away_club_goals
        FROM %s WHERE CAST(game_id AS BIGINT) = ?
        """;

    private static final String LINEUP_SQL = """
        SELECT club_id, player_id, player_name, position, "type"
        FROM game_lineups WHERE CAST(game_id AS BIGINT) = ?
        ORDER BY club_id, player_id
        """;

    private static final String EVENTS_SQL = """
        SELECT minute, "type", club_id, player_id, player_in_id, description
        FROM game_events WHERE CAST(game_id AS BIGINT) = ?
        ORDER BY minute
        """;

    private static final String APPEARANCES_SQL = """
        SELECT minutes_played FROM appearances
        WHERE CAST(game_id AS BIGINT) = ? ORDER BY minutes_played
        """;

    // The appeared roster (stage 4b-3): everyone the vendor's appearances named,
    // with a position looked up from players so the goalkeeper is known. The join
    // is a LEFT join and drops no roster player (100% carry a position on the
    // reconstructable games, measured 2026-07-26); a rare miss becomes 'Unknown'
    // rather than null so the sidecar never stores a null position. The type here
    // is a placeholder - fromAppearances decides start/bench from the events.
    private static final String ROSTER_SQL = """
        SELECT a.player_club_id AS club_id, a.player_id, a.player_name,
               COALESCE(p.position, 'Unknown') AS position
        FROM appearances a
        LEFT JOIN players p ON p.player_id = a.player_id
        WHERE CAST(a.game_id AS BIGINT) = ?
        ORDER BY a.player_club_id, a.player_id
        """;

    // Load a match for editing: the sidecar draft if one exists, else the vendor
    // (decision 6) - without which "save as draft" would be write-only and you
    // could not see your own work. A vendor match with a team sheet is the certain
    // path; one without is the appeared tier, reconstructed from its records (stage
    // 4b-3). The caller never learns which - it just gets an EditableMatch.
    public EditableMatch load(long gameId) throws SQLException {
        if (hasDraft(gameId)) {
            try (Connection c = openReadOnly(sidecar)) {
                return readMatch(c, "matches", gameId);
            }
        }
        try (Connection c = openReadOnly(snapshot)) {
            if (hasVendorLineup(c, gameId)) {
                return readMatch(c, "games", gameId);
            }
            return reconstructAppeared(c, gameId);
        }
    }

    private static final String APPEARED_IDS_SQL = """
        SELECT DISTINCT CAST(a.game_id AS BIGINT) AS gid
        FROM appearances a
        WHERE NOT EXISTS (
            SELECT 1 FROM game_lineups gl WHERE CAST(gl.game_id AS BIGINT) = a.game_id)
        ORDER BY gid
        """;

    // Every appeared game - appearances present, no vendor team sheet - the bulk
    // reconstruction's candidate set (stage 4b-4), read from the vendor once.
    public List<Long> appearedGameIds() throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Connection c = openReadOnly(snapshot);
            PreparedStatement ps = c.prepareStatement(APPEARED_IDS_SQL);
            ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getLong("gid"));
            }
        }
        return out;
    }

    // The game ids already in the sidecar, any status, so the batch can skip them
    // and never overwrite a hand-made or already-released match. Empty when the
    // sidecar is absent or has no matches table.
    public Set<Long> sidecarGameIds() throws SQLException {
        Set<Long> out = new HashSet<>();
        if (!Files.exists(sidecar)) {
            return out;
        }
        try (Connection c = openReadOnly(sidecar);
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery("SELECT game_id FROM matches")) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        } catch (SQLException noSuchTable) {
            // A file with no matches table holds no releases.
        }
        return out;
    }

    // Reconstruct many appeared matches over a single vendor connection - far
    // cheaper than one open per game for a batch of thousands.
    public List<EditableMatch> reconstructAppeared(List<Long> gameIds) throws SQLException {
        List<EditableMatch> out = new ArrayList<>(gameIds.size());
        try (Connection c = openReadOnly(snapshot)) {
            for (long id : gameIds) {
                out.add(reconstructAppeared(c, id));
            }
        }
        return out;
    }

    private boolean hasVendorLineup(Connection c, long gameId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM game_lineups WHERE CAST(game_id AS BIGINT) = ? LIMIT 1")) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Build an appeared match from the vendor's own records: the games header, the
    // appearances roster, and the events (which fromAppearances reads to split
    // starters from subs). The appearances minutes are copied through as for any
    // released match.
    private EditableMatch reconstructAppeared(Connection c, long gameId) throws SQLException {
        return EditableMatch.fromAppearances(readHeader(c, "games", gameId),
            readRoster(c, gameId), readEvents(c, gameId), readAppearances(c, gameId));
    }

    private List<LineupEntry> readRoster(Connection c, long gameId) throws SQLException {
        List<LineupEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(ROSTER_SQL)) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LineupEntry(rs.getLong("club_id"), rs.getLong("player_id"),
                        rs.getString("player_name"), rs.getString("position"),
                        LineupEntry.BENCH));
                }
            }
        }
        return out;
    }

    private boolean hasDraft(long gameId) throws SQLException {
        if (!Files.exists(sidecar)) {
            return false;
        }
        try (Connection c = openReadOnly(sidecar);
            PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM matches WHERE CAST(game_id AS BIGINT) = ?")) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException noSuchTable) {
            // A file that exists but holds no matches table is not a draft.
            return false;
        }
    }

    private EditableMatch readMatch(Connection c, String matchTable, long gameId)
        throws SQLException {
        return new EditableMatch(readHeader(c, matchTable, gameId),
            readLineups(c, gameId), readEvents(c, gameId), readAppearances(c, gameId));
    }

    private MatchHeader readHeader(Connection c, String table, long gameId)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(HEADER_SQL.formatted(table))) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("no match " + gameId + " in " + table);
                }
                return new MatchHeader(gameId, rs.getDate("date").toLocalDate(),
                    rs.getString("competition_id"), rs.getString("season"),
                    rs.getString("round"), rs.getString("competition_type"),
                    rs.getLong("home_club_id"), rs.getString("home_club_name"),
                    rs.getLong("away_club_id"), rs.getString("away_club_name"),
                    rs.getInt("home_club_goals"), rs.getInt("away_club_goals"));
            }
        }
    }

    private List<LineupEntry> readLineups(Connection c, long gameId) throws SQLException {
        List<LineupEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(LINEUP_SQL)) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LineupEntry(rs.getLong("club_id"), rs.getLong("player_id"),
                        rs.getString("player_name"), rs.getString("position"),
                        rs.getString("type")));
                }
            }
        }
        return out;
    }

    private List<EventRow> readEvents(Connection c, long gameId) throws SQLException {
        List<EventRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(EVENTS_SQL)) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long in = rs.getLong("player_in_id");
                    Long playerIn = rs.wasNull() ? null : in;
                    out.add(new EventRow(gameId, rs.getInt("minute"), rs.getString("type"),
                        rs.getLong("club_id"), rs.getLong("player_id"), playerIn,
                        rs.getString("description")));
                }
            }
        }
        return out;
    }

    private List<AppearanceRow> readAppearances(Connection c, long gameId)
        throws SQLException {
        List<AppearanceRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(APPEARANCES_SQL)) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AppearanceRow(gameId, rs.getInt("minutes_played")));
                }
            }
        }
        return out;
    }

    // The write. A save is a whole-match replacement (ADR 0009): the game's rows
    // are deleted from all four tables and rewritten, so a second save replaces
    // rather than duplicates and a released match is always exactly what was on
    // screen. status is the caller's ('draft' or 'released'); commit_hash is
    // stamped from the vendor's own version table so a repair records which
    // snapshot it was made against. The whole thing is one transaction: a failed
    // save leaves the sidecar as it was, never half-written.
    public void save(EditableMatch match, String status, String provenance)
        throws SQLException {
        long gameId = match.header().gameId();
        try (Connection c = openWritable(sidecar)) {
            ensureSchema(c);
            String commitHash = vendorCommitHash(c);
            c.setAutoCommit(false);
            try {
                deleteGame(c, gameId);
                insertMatch(c, match.header(), status, provenance, commitHash);
                insertLineups(c, gameId, match.lineup());
                insertEvents(c, gameId, match.events());
                insertAppearances(c, gameId, match.appearances());
                insertManualPlayers(c, match.created());
                c.commit();
            } catch (SQLException failed) {
                c.rollback();
                throw failed;
            }
        }
    }

    // The batch write (stage 4b-4): every match released in one transaction over
    // one connection, so thousands of reconstructions cost one open, one commit_hash
    // read and one all-or-nothing commit. Each match keeps its own generated
    // provenance with provenanceSuffix appended, so the whole set is queryable and
    // undoable by that tag. Reuses the same per-match inserts as save, so a batch
    // row is byte-identical to one written through the editor.
    public void saveAll(List<EditableMatch> matches, String status, String provenanceSuffix)
        throws SQLException {
        try (Connection c = openWritable(sidecar)) {
            ensureSchema(c);
            String commitHash = vendorCommitHash(c);
            c.setAutoCommit(false);
            try {
                for (EditableMatch match : matches) {
                    long gameId = match.header().gameId();
                    String provenance = provenanceSuffix.isEmpty()
                        ? match.provenanceSummary()
                        : match.provenanceSummary() + " " + provenanceSuffix;
                    deleteGame(c, gameId);
                    insertMatch(c, match.header(), status, provenance, commitHash);
                    insertLineups(c, gameId, match.lineup());
                    insertEvents(c, gameId, match.events());
                    insertAppearances(c, gameId, match.appearances());
                    insertManualPlayers(c, match.created());
                }
                c.commit();
            } catch (SQLException failed) {
                c.rollback();
                throw failed;
            }
        }
    }

    private void ensureSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String ddl : SCHEMA) {
                s.execute(ddl);
            }
        }
    }

    private String vendorCommitHash(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ATTACH '" + snapshot.toString().replace('\\', '/')
                + "' AS vendor (READ_ONLY)");
            try (ResultSet rs = s.executeQuery("SELECT commit_hash FROM vendor.version")) {
                return rs.next() ? rs.getString("commit_hash") : null;
            }
        }
    }

    private void deleteGame(Connection c, long gameId) throws SQLException {
        for (String table : List.of("matches", "game_lineups", "game_events", "appearances")) {
            try (PreparedStatement ps =
                c.prepareStatement("DELETE FROM " + table + " WHERE game_id = ?")) {
                ps.setLong(1, gameId);
                ps.executeUpdate();
            }
        }
    }

    private void insertMatch(Connection c, MatchHeader h, String status,
        String provenance, String commitHash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO matches (game_id, status, date, competition_id, season, round,
                competition_type, home_club_id, home_club_name, away_club_id,
                away_club_name, home_club_goals, away_club_goals, provenance, commit_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setLong(1, h.gameId());
            ps.setString(2, status);
            ps.setDate(3, Date.valueOf(h.date()));
            ps.setString(4, h.competitionId());
            ps.setString(5, h.season());
            ps.setString(6, h.round());
            ps.setString(7, h.competitionType());
            ps.setLong(8, h.homeClubId());
            ps.setString(9, h.homeClubName());
            ps.setLong(10, h.awayClubId());
            ps.setString(11, h.awayClubName());
            ps.setInt(12, h.homeClubGoals());
            ps.setInt(13, h.awayClubGoals());
            ps.setString(14, provenance);
            ps.setString(15, commitHash);
            ps.executeUpdate();
        }
    }

    private void insertLineups(Connection c, long gameId, List<LineupEntry> lineup)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO game_lineups (game_id, club_id, player_id, player_name, position, "type")
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            for (LineupEntry e : lineup) {
                ps.setLong(1, gameId);
                ps.setLong(2, e.clubId());
                ps.setLong(3, e.playerId());
                ps.setString(4, e.playerName());
                ps.setString(5, e.position());
                ps.setString(6, e.type());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertEvents(Connection c, long gameId, List<EventRow> events)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO game_events (game_id, minute, "type", club_id, player_id,
                player_in_id, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            for (EventRow e : events) {
                ps.setLong(1, gameId);
                ps.setInt(2, e.minute());
                ps.setString(3, e.type());
                ps.setLong(4, e.clubId());
                ps.setLong(5, e.playerId());
                if (e.playerInId() == null) {
                    ps.setNull(6, Types.BIGINT);
                } else {
                    ps.setLong(6, e.playerInId());
                }
                ps.setString(7, e.description());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertAppearances(Connection c, long gameId, List<AppearanceRow> rows)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO appearances (game_id, minutes_played) VALUES (?, ?)")) {
            for (AppearanceRow a : rows) {
                ps.setLong(1, gameId);
                ps.setInt(2, a.minutesPlayed());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // The register write (ADR 0012, decision 3). It rides inside the caller's
    // transaction, so a repair that is cancelled - or one that fails halfway -
    // leaves no manual player behind, and every row here belongs to a match that
    // really was saved. A re-save of the same match finds its players already
    // registered and adds nothing, which keeps the created_on honest: it is when
    // the man was first written, not when his match was last touched.
    private void insertManualPlayers(Connection c, List<ManualPlayer> players)
        throws SQLException {
        if (players.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO manual_players (player_id, player_name, date_of_birth, created_on, note)
            SELECT ?, ?, ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM manual_players WHERE player_id = ?)
            """)) {
            for (ManualPlayer p : players) {
                ps.setLong(1, p.playerId());
                ps.setString(2, p.playerName());
                if (p.dateOfBirth() == null) {
                    ps.setNull(3, Types.DATE);
                } else {
                    ps.setDate(3, Date.valueOf(p.dateOfBirth()));
                }
                ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
                ps.setString(5, p.note());
                ps.setLong(6, p.playerId());
                ps.executeUpdate();
            }
        }
    }

    // --- The picker's candidates (item 17, slice 1, decision 10) --------------
    //
    // SQL selects and Java ranks: these queries gather evidence about who might be
    // the man being named, and CandidateRanker in the repair package turns that
    // evidence into 'Candidate rank'. The split is deliberate - the ranking rule
    // will be tuned, and a rule inside a SQL string can only ever be exercised by
    // the real snapshot.
    //
    // The club arm. Who has turned out for this club, over vendor appearances and
    // sidecar lineups together (decision 5), each match counted once by DISTINCT
    // game_id so the 4,573 bulk reconstructions do not double-count. The nearby
    // count is the same union narrowed to a month either side of this match, which
    // is rank 0. The match being repaired is excluded, so a draft's own rows never
    // rank their own players. %s is the sidecar half, dropped when there is no
    // sidecar yet.
    //
    // Sidecar drafts count beside releases, deliberately: a rank is a typing aid
    // and never evidence (decision 1), and a man entered into yesterday's
    // half-finished repair is exactly the man you want offered first today. Nothing
    // here reaches a rating - only a Released match does.
    //
    // This arm alone is uncapped, where decision 10 caps the reader. The cap is
    // there to keep rank 2's 114,893 players off the screen; capping a club's own
    // squad would instead drop rank-0 names, and truncating it in SQL by nearby
    // count would put the ranking rule back in the query string that decision 10
    // took it out of.
    private static final String CANDIDATES_SQL = """
        WITH plays AS (
            SELECT a.player_id AS player_id, CAST(a.game_id AS BIGINT) AS game_id,
                   a.date AS played_on, a.player_name AS player_name,
                   CAST(NULL AS VARCHAR) AS position
            FROM appearances a
            WHERE a.player_club_id = ? AND CAST(a.game_id AS BIGINT) <> ?
            %s
        ),
        club AS (
            SELECT player_id, min(player_name) AS player_name,
                   max(position) AS position,
                   count(DISTINCT game_id) FILTER (WHERE played_on BETWEEN ? AND ?) AS nearby
            FROM plays GROUP BY player_id
        )
        SELECT c.player_id,
               COALESCE(v.name, %s, c.player_name) AS player_name,
               COALESCE(v.position, c.position, 'Unknown') AS position,
               COALESCE(CAST(v.date_of_birth AS DATE), %s) AS date_of_birth,
               c.nearby AS nearby
        FROM club c
        LEFT JOIN players v ON v.player_id = c.player_id
        %s
        """;

    private static final String SIDECAR_PLAYS = """
            UNION ALL
            SELECT l.player_id, l.game_id, m.date, l.player_name, l.position
            FROM side.game_lineups l JOIN side.matches m ON m.game_id = l.game_id
            WHERE l.club_id = ? AND l.game_id <> ?
        """;

    // The everyone-else arm (rank 2), asked only once something is typed: the
    // vendor's 114,893 players are unusable as an opening list, and a name search
    // over them is what turns a rank-2 guess into one keystroke.
    private static final String SEARCH_VENDOR_SQL = """
        SELECT v.player_id, v.name AS player_name,
               COALESCE(v.position, 'Unknown') AS position,
               CAST(v.date_of_birth AS DATE) AS date_of_birth
        FROM players v
        WHERE lower(v.name) LIKE '%' || lower(?) || '%'
        ORDER BY v.name
        LIMIT ?
        """;

    // The manual half of the same arm. A hand-made player has no players row, so a
    // man created for one club is otherwise unfindable when he turns up at another.
    private static final String SEARCH_MANUAL_SQL = """
        SELECT mp.player_id, mp.player_name, 'Unknown' AS position, mp.date_of_birth
        FROM side.manual_players mp
        WHERE lower(mp.player_name) LIKE '%' || lower(?) || '%'
        ORDER BY mp.player_name
        LIMIT ?
        """;

    // Everyone the picker may offer for one side of one match, tagged with their
    // evidence and unranked. Rank 0 - the club's squad within a month of the date -
    // always comes back, so a side with nobody in it opens as a pre-ranked list of
    // eleven clicks. The everyone-else arm is added only once text is typed, and
    // capped there.
    public List<PlayerCandidate> candidates(long clubId, long gameId, LocalDate date,
        String typed, int cap) throws SQLException {

        List<PlayerCandidate> out = new ArrayList<>();
        try (Connection c = openReadOnly(snapshot)) {
            Set<String> side = attachSidecar(c);
            boolean withLineups = side.contains("game_lineups") && side.contains("matches");
            boolean withRegister = side.contains("manual_players");
            readClubArm(c, out, clubId, gameId, date, withLineups, withRegister);
            if (typed != null && !typed.isBlank()) {
                readSearchArm(c, out, SEARCH_VENDOR_SQL, typed, cap);
                if (withRegister) {
                    readSearchArm(c, out, SEARCH_MANUAL_SQL, typed, cap);
                }
            }
        }
        return out;
    }

    // Attach the sidecar read-only beside the vendor and report which of its tables
    // are really there. Three states have to survive: no file at all before the
    // first repair, a file written before ADR 0012 that has four tables and no
    // register, and a current one with five. Naming a missing table in a query is a
    // hard error, so each arm is gated on what came back rather than on the file
    // merely existing.
    private Set<String> attachSidecar(Connection c) throws SQLException {
        Set<String> tables = new HashSet<>();
        if (!Files.exists(sidecar)) {
            return tables;
        }
        try (Statement s = c.createStatement()) {
            s.execute("ATTACH '" + sidecar.toString().replace('\\', '/')
                + "' AS side (READ_ONLY)");
            try (ResultSet rs = s.executeQuery(
                "SELECT table_name FROM duckdb_tables() WHERE database_name = 'side'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private void readClubArm(Connection c, List<PlayerCandidate> out, long clubId,
        long gameId, LocalDate date, boolean withLineups, boolean withRegister)
        throws SQLException {

        // The register comes before the man's own lineup rows in the name COALESCE:
        // it is authoritative for the name (ADR 0012, decision 4), while a released
        // match is a snapshot that keeps whatever name it was saved with, so a name
        // corrected in the register still reaches the picker.
        String sql = CANDIDATES_SQL.formatted(
            withLineups ? SIDECAR_PLAYS : "",
            withRegister ? "mp.player_name" : "NULL",
            withRegister ? "mp.date_of_birth" : "NULL",
            withRegister ? "LEFT JOIN side.manual_players mp ON mp.player_id = c.player_id" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int p = 1;
            ps.setLong(p++, clubId);
            ps.setLong(p++, gameId);
            if (withLineups) {
                ps.setLong(p++, clubId);
                ps.setLong(p++, gameId);
            }
            ps.setDate(p++, Date.valueOf(date.minusDays(30)));
            ps.setDate(p, Date.valueOf(date.plusDays(30)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(candidate(rs, rs.getInt("nearby"), true));
                }
            }
        }
    }

    private void readSearchArm(Connection c, List<PlayerCandidate> out, String sql,
        String typed, int cap) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, typed);
            ps.setInt(2, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(candidate(rs, 0, false));
                }
            }
        }
    }

    private static PlayerCandidate candidate(ResultSet rs, int nearby, boolean everForClub)
        throws SQLException {
        Date dob = rs.getDate("date_of_birth");
        return new PlayerCandidate(rs.getLong("player_id"), rs.getString("player_name"),
            rs.getString("position"), dob == null ? null : dob.toLocalDate(),
            nearby, everForClub);
    }

    // The single read the editor's id allocation stands on (ADR 0012, decision 3):
    // the highest id the register already holds, taken once when a repair opens, so
    // ids within that repair are max+1, max+2 and cannot collide with an earlier
    // session's. Zero when the sidecar, or the table, is not there yet.
    // A zero here restarts allocation at the first reserved id, which would re-mint
    // ids already registered and split exactly the careers ADR 0012 exists to keep
    // whole. So it is returned only for the two states that really do mean "no
    // manual player yet" - no sidecar file, and a sidecar written before ADR 0012
    // that has no register table. Anything else (a locked file, a corrupt one) is
    // thrown, and the editor refuses to open rather than opening on a wrong ceiling.
    public long highestManualPlayerId() throws SQLException {
        if (!Files.exists(sidecar)) {
            return 0;
        }
        try (Connection c = openReadOnly(sidecar);
            Statement s = c.createStatement()) {
            try (ResultSet present = s.executeQuery(
                "SELECT 1 FROM duckdb_tables() WHERE table_name = 'manual_players'")) {
                if (!present.next()) {
                    return 0;
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT max(player_id) FROM manual_players")) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private Connection openReadOnly(Path db) throws SQLException {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + db, readOnly);
    }

    private Connection openWritable(Path db) throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + db);
    }
}
