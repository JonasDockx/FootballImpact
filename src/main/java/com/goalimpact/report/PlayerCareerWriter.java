package com.goalimpact.report;

import com.goalimpact.engine.PlayerTally;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;

// One row per player the run rated, carrying the career-level facts that are
// true of a man rather than of a match (ADR 0016, #44). Today that is one fact:
// whether he is a Goalkeeper - the glossary's career tag, sticky from the first
// time he starts in goal.
//
// The REPLAY records it and the viewer reads it back, rather than the viewer
// re-deriving it from the vendor's lineups. Two reasons, and the second is the
// one that bites: it would be a second copy of a definition, and it can
// genuinely disagree - the SQL sees matches the run dropped as unusable, so a
// man who only ever started in goal in a discarded match would be a Goalkeeper
// to the chart and a field player to the model that rated him. Same argument
// that keeps the ageing curve's knot table out of SQL.
//
// Its own table rather than a column on rating_history, which is one row per
// player-match and would repeat the flag two million times, and rather than a
// column on appeared_players, which is a worklist tier over a different
// population entirely. Written for the DESIGNATED run only, beside the history
// it belongs to, and rebuilt whole like everything else in the disposable
// results file.
public final class PlayerCareerWriter {

    private PlayerCareerWriter() {
    }

    public static long write(Path results, String runId, Collection<PlayerTally> tallies)
        throws SQLException {

        long rows = 0;
        try (DuckDBConnection connection =
                (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + results)) {
            try (Statement s = connection.createStatement()) {
                // Replaced, not appended to, for the same reason the history is:
                // a career tag is only true of the run that stamped it, and a
                // half-overwritten table is indistinguishable from a real one.
                s.execute("DROP TABLE IF EXISTS player_careers");
                s.execute("""
                    CREATE TABLE player_careers (
                        run_id      VARCHAR,
                        player_id   BIGINT,
                        goalkeeper  BOOLEAN
                    )""");
            }
            try (DuckDBAppender appender = connection.createAppender(
                    DuckDBConnection.DEFAULT_SCHEMA, "player_careers")) {
                for (PlayerTally tally : tallies) {
                    appender.beginRow();
                    appender.append(runId);
                    appender.append(tally.player().id());
                    appender.append(tally.isGoalkeeper());
                    appender.endRow();
                    rows++;
                }
            }
        }
        return rows;
    }
}
