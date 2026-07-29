package com.goalimpact.report;

import com.goalimpact.data.HeldMatch;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

// The match-level spine of the worklist (item 29, slice 1), written to the
// results file beside the three player tables. Those three are reachable only
// through a player's name, so a Held match nobody names appears in none of them
// - 46% of them, measured 2026-07-28. This table has a row for every Held match
// whatever the reason, and every row carries both club ids, so a club-scoped
// worklist reaches all of them by construction.
//
// Recomputed whole every designated run like its neighbours: Held is a verdict
// the gate reaches on each run, never a stored decision (CONTEXT 'Match state'),
// so DROP-and-rewrite in the disposable results DB is exactly right. Match facts
// (date, competition, club names) stay in the vendor games table and are joined
// at display time, so a snapshot refresh cannot leave this quoting a stale date.
public final class HeldMatchWriter {

    private HeldMatchWriter() {
    }

    public static long write(Path results, String runId, List<HeldMatch> matches)
        throws SQLException {

        try (DuckDBConnection connection =
                (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + results)) {
            try (Statement s = connection.createStatement()) {
                s.execute("DROP TABLE IF EXISTS held_matches");
                s.execute("""
                    CREATE TABLE held_matches (
                        run_id         VARCHAR,
                        game_id        BIGINT,
                        home_club_id   BIGINT,
                        away_club_id   BIGINT,
                        reason         VARCHAR,
                        repair_source  VARCHAR
                    )""");
            }
            try (DuckDBAppender appender = connection.createAppender(
                    DuckDBConnection.DEFAULT_SCHEMA, "held_matches")) {
                for (HeldMatch m : matches) {
                    appender.beginRow();
                    appender.append(runId);
                    appender.append(m.gameId());
                    appender.append(m.homeClubId());
                    appender.append(m.awayClubId());
                    appender.append(m.reason());
                    appender.append(m.repairSource().name());
                    appender.endRow();
                }
            }
        }
        return matches.size();
    }
}
