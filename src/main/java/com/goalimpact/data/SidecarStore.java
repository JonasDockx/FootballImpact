package com.goalimpact.data;

import com.goalimpact.repair.AppearanceRow;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.EventRow;
import com.goalimpact.repair.LineupEntry;
import com.goalimpact.repair.MatchHeader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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

    // Load a match for editing: the sidecar draft if one exists, else the vendor
    // (decision 6) - without which "save as draft" would be write-only and you
    // could not see your own work.
    public EditableMatch load(long gameId) throws SQLException {
        if (hasDraft(gameId)) {
            try (Connection c = openReadOnly(sidecar)) {
                return readMatch(c, "matches", gameId);
            }
        }
        try (Connection c = openReadOnly(snapshot)) {
            return readMatch(c, "games", gameId);
        }
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

    private Connection openReadOnly(Path db) throws SQLException {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + db, readOnly);
    }

    private Connection openWritable(Path db) throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:" + db);
    }
}
