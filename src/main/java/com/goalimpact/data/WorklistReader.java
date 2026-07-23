package com.goalimpact.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

// The read side of the per-player worklist (item 26, stage 4b-1), and the only
// place stage 4b knows what a database is - CLAUDE.md keeps that knowledge in
// data, so the gui package holds no SQL exactly as data holds no JavaFX.
//
// Two files, both opened read-only. The worklist itself lives in the disposable
// results DB (three tables, rebuilt whole every designated run), and the match
// facts live in the vendor snapshot, attached here and joined at display time so
// a snapshot refresh can never leave the worklist quoting a stale date (item
// 25). Read-only on both sides is the structural half of "4b-1 writes nothing";
// the sidecar is not opened at all.
//
// The three rungs come back as three lists, each ordered most recent first, and
// are never merged: a row's meaning differs per rung and each carries its own
// ranking signal (CONTEXT 'Worklist tier').
public class WorklistReader implements AutoCloseable {

    // The worklist tables store game_id as BIGINT while games.game_id is
    // VARCHAR, so every join casts - the same type trap the loader documents.
    // The game id itself is selected from the worklist side, where it is
    // already a number.
    private static final String MATCH_FACTS =
        "g.date, g.competition_id, g.round, g.home_club_name, g.away_club_name";

    private static final String CERTAIN_SQL = """
        SELECT h.game_id, %s, h.club_id, h.started, h.reason
        FROM held_appearances h
        JOIN vendor.games g ON g.game_id = CAST(h.game_id AS VARCHAR)
        WHERE h.player_id = ?
        ORDER BY g.date DESC, h.game_id
        """.formatted(MATCH_FACTS);

    private static final String APPEARED_SQL = """
        SELECT a.game_id, %s, a.club_id, a.minutes
        FROM appeared_players a
        JOIN vendor.games g ON g.game_id = CAST(a.game_id AS VARCHAR)
        WHERE a.player_id = ?
        ORDER BY g.date DESC, a.game_id
        """.formatted(MATCH_FACTS);

    private static final String MAYBE_SQL = """
        SELECT m.game_id, %s, m.club_id, m.nearby_matches
        FROM maybe_players m
        JOIN vendor.games g ON g.game_id = CAST(m.game_id AS VARCHAR)
        WHERE m.player_id = ?
        ORDER BY g.date DESC, m.game_id
        """.formatted(MATCH_FACTS);

    // One player may hold rows in any of the three rungs, so every player-shaped
    // question asks all three at once.
    private static final String ALL_NAMES = """
        SELECT player_id, player_name FROM held_appearances
        UNION ALL SELECT player_id, player_name FROM appeared_players
        UNION ALL SELECT player_id, player_name FROM maybe_players
        """;

    // Searching the worklist's own names rather than the vendor players table:
    // it can only offer a player there is work for, at the known cost that a
    // miss cannot tell a complete career from a typo (item 26, stage 4b-1).
    // min() rather than any_value() because 1,075 player ids carry more than one
    // name (ADR 0009), and an arbitrary pick would make the screen flicker.
    private static final String SEARCH_SQL = """
        SELECT player_id, min(player_name) AS player_name, count(*) AS missing_matches
        FROM (%s)
        WHERE lower(player_name) LIKE '%%' || lower(?) || '%%'
        GROUP BY player_id
        ORDER BY missing_matches DESC, player_name
        LIMIT 200
        """.formatted(ALL_NAMES);

    private static final String NAME_SQL = """
        SELECT min(player_name) AS player_name FROM (%s) WHERE player_id = ?
        """.formatted(ALL_NAMES);

    // All three tables are dropped and rewritten by the same designated run, so
    // there is never more than one run_id in the file. Surfaced so the screen
    // can show whether the worklist it is drawing is stale.
    private static final String RUN_SQL = """
        SELECT max(run_id) AS run_id FROM (
            SELECT run_id FROM held_appearances
            UNION ALL SELECT run_id FROM appeared_players
            UNION ALL SELECT run_id FROM maybe_players)
        """;

    private final Connection connection;

    public WorklistReader(Path results, Path snapshot) throws SQLException {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        this.connection = DriverManager.getConnection("jdbc:duckdb:" + results, readOnly);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ATTACH '"
                + snapshot.toString().replace('\\', '/') + "' AS vendor (READ_ONLY)");
        }
    }

    public String runId() throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(RUN_SQL)) {
            String runId = rows.next() ? rows.getString("run_id") : null;
            return runId == null ? "unknown" : runId;
        }
    }

    public List<WorklistPlayer> searchPlayers(String typed) throws SQLException {
        List<WorklistPlayer> hits = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SEARCH_SQL)) {
            statement.setString(1, typed);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    hits.add(new WorklistPlayer(rows.getLong("player_id"),
                        rows.getString("player_name"), rows.getInt("missing_matches")));
                }
            }
        }
        return hits;
    }

    public Worklist worklistFor(long playerId) throws SQLException {
        return new Worklist(playerId, playerName(playerId),
            certain(playerId), appeared(playerId), maybe(playerId));
    }

    private List<CertainRow> certain(long playerId) throws SQLException {
        List<CertainRow> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CERTAIN_SQL)) {
            statement.setLong(1, playerId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new CertainRow(matchFacts(rows), rows.getLong("club_id"),
                        rows.getBoolean("started"), rows.getString("reason")));
                }
            }
        }
        return out;
    }

    private List<AppearedRow> appeared(long playerId) throws SQLException {
        List<AppearedRow> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(APPEARED_SQL)) {
            statement.setLong(1, playerId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new AppearedRow(matchFacts(rows), rows.getLong("club_id"),
                        rows.getInt("minutes")));
                }
            }
        }
        return out;
    }

    private List<MaybeRow> maybe(long playerId) throws SQLException {
        List<MaybeRow> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(MAYBE_SQL)) {
            statement.setLong(1, playerId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new MaybeRow(matchFacts(rows), rows.getLong("club_id"),
                        rows.getInt("nearby_matches")));
                }
            }
        }
        return out;
    }

    private String playerName(long playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(NAME_SQL)) {
            statement.setLong(1, playerId);
            try (ResultSet rows = statement.executeQuery()) {
                String name = rows.next() ? rows.getString("player_name") : null;
                return name == null ? "" : name;
            }
        }
    }

    private static MatchFacts matchFacts(ResultSet rows) throws SQLException {
        return new MatchFacts(rows.getLong("game_id"), rows.getDate("date").toLocalDate(),
            rows.getString("competition_id"), rows.getString("round"),
            rows.getString("home_club_name"), rows.getString("away_club_name"));
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
