package com.goalimpact.report;

import com.goalimpact.data.HeldAppearance;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

// The certain-tier worklist (item 25), written to the results file beside
// rating_history: one row per player named in a Held match, rebuilt whole on
// every designated run. Disposable and read-only - the Held set is recomputed
// from the gate every run (item 26), never a stored decision, so dropping and
// rewriting it in the disposable results DB is exactly right. Match facts are
// deliberately NOT stored; they live in the vendor games table.
public final class HeldAppearanceWriter {

    private HeldAppearanceWriter() {
    }

    public static long write(Path results, String runId, List<HeldAppearance> held)
        throws SQLException {

        try (DuckDBConnection connection =
                (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + results)) {
            try (Statement s = connection.createStatement()) {
                s.execute("DROP TABLE IF EXISTS held_appearances");
                s.execute("""
                    CREATE TABLE held_appearances (
                        run_id       VARCHAR,
                        player_id    BIGINT,
                        player_name  VARCHAR,
                        club_id      BIGINT,
                        game_id      BIGINT,
                        reason       VARCHAR,
                        started      BOOLEAN
                    )""");
            }
            try (DuckDBAppender appender = connection.createAppender(
                    DuckDBConnection.DEFAULT_SCHEMA, "held_appearances")) {
                for (HeldAppearance h : held) {
                    appender.beginRow();
                    appender.append(runId);
                    appender.append(h.playerId());
                    appender.append(h.playerName());
                    appender.append(h.clubId());
                    appender.append(h.gameId());
                    appender.append(h.reason());
                    appender.append(h.started());
                    appender.endRow();
                }
            }
            return held.size();
        }
    }
}
