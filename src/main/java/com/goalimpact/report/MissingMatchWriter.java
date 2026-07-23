package com.goalimpact.report;

import com.goalimpact.data.AppearedPlayer;
import com.goalimpact.data.MaybePlayer;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

// The appeared and maybe tiers of the per-player worklist (item 26, stage 4a),
// written to the results file beside held_appearances: the two lower rungs of
// the confidence ladder (CONTEXT 'Worklist tier'). Two tables, not one, because
// a row's meaning differs - an appeared row is a player who really played, with
// minutes; a maybe row is only a candidate, ranked by how many nearby matches
// he turned out for. Both are recomputed whole every designated run (item 26),
// never a stored decision, so DROP-and-rewrite in the disposable results DB is
// exactly right. Match facts (date, competition, opponent) stay in the vendor
// games table and are joined at display time, exactly as for held_appearances.
public final class MissingMatchWriter {

    private MissingMatchWriter() {
    }

    public static void write(Path results, String runId,
        List<AppearedPlayer> appeared, List<MaybePlayer> maybe) throws SQLException {

        try (DuckDBConnection connection =
                (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + results)) {
            try (Statement s = connection.createStatement()) {
                s.execute("DROP TABLE IF EXISTS appeared_players");
                s.execute("""
                    CREATE TABLE appeared_players (
                        run_id       VARCHAR,
                        player_id    BIGINT,
                        player_name  VARCHAR,
                        club_id      BIGINT,
                        game_id      BIGINT,
                        minutes      INTEGER
                    )""");
                s.execute("DROP TABLE IF EXISTS maybe_players");
                s.execute("""
                    CREATE TABLE maybe_players (
                        run_id          VARCHAR,
                        player_id       BIGINT,
                        player_name     VARCHAR,
                        club_id         BIGINT,
                        game_id         BIGINT,
                        nearby_matches  INTEGER
                    )""");
            }
            try (DuckDBAppender appender = connection.createAppender(
                    DuckDBConnection.DEFAULT_SCHEMA, "appeared_players")) {
                for (AppearedPlayer a : appeared) {
                    appender.beginRow();
                    appender.append(runId);
                    appender.append(a.playerId());
                    appender.append(a.playerName());
                    appender.append(a.clubId());
                    appender.append(a.gameId());
                    appender.append(a.minutes());
                    appender.endRow();
                }
            }
            try (DuckDBAppender appender = connection.createAppender(
                    DuckDBConnection.DEFAULT_SCHEMA, "maybe_players")) {
                for (MaybePlayer m : maybe) {
                    appender.beginRow();
                    appender.append(runId);
                    appender.append(m.playerId());
                    appender.append(m.playerName());
                    appender.append(m.clubId());
                    appender.append(m.gameId());
                    appender.append(m.nearbyMatches());
                    appender.endRow();
                }
            }
        }
    }
}
