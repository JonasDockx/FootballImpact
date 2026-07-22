package com.goalimpact.report;

import com.goalimpact.engine.MatchObserver;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

// ADR 0011: the results file, which ADR 0009 designed and left unbuilt - the
// third lifecycle beside the read-only vendor snapshot and the precious
// sidecar. Disposable by definition: rebuilt whole by any run that wants it,
// never hand-edited, safe to delete.
//
// Written for the DESIGNATED run only, never for a grid cell. One run is
// ~2M rows; an 81-cell grid would be 160M+ for a search whose answer is one
// log-loss per cell.
public final class RatingHistoryWriter implements MatchObserver, AutoCloseable {

    private final DuckDBConnection connection;
    private final DuckDBAppender appender;
    private final String runId;

    private long matchId;
    private LocalDate matchDate;
    private long rows;

    public RatingHistoryWriter(Path results, String runId) throws SQLException {
        this.runId = runId;
        this.connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + results);
        try (Statement s = connection.createStatement()) {
            // Replaced, not appended to: a half-overwritten history is
            // indistinguishable from a real one, and every rating in it is
            // only true relative to the run that produced it.
            s.execute("DROP TABLE IF EXISTS rating_history");
            s.execute("""
                CREATE TABLE rating_history (
                    run_id          VARCHAR,
                    player_id       BIGINT,
                    match_id        BIGINT,
                    match_date      DATE,
                    minutes_before  DOUBLE,
                    rating_before   DOUBLE,
                    residual        DOUBLE,
                    minutes_played  DOUBLE,
                    rating_after    DOUBLE
                )""");
        }
        this.appender = connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "rating_history");
    }

    @Override
    public void startMatch(long matchId, LocalDate date) {
        this.matchId = matchId;
        this.matchDate = date;
    }

    // MatchObserver declares no checked exception, and must not: the engine
    // is not allowed to learn that its listener talks to a database
    // (ADR 0004). A failed write is still fatal - a history that is quietly
    // short is worse than no history, because it still draws a chart.
    @Override
    public void playerMatch(long playerId, double minutesBefore, double ratingBefore,
        double residual, double minutesPlayed, double ratingAfter) {

        try {
            appender.beginRow();
            appender.append(runId);
            appender.append(playerId);
            appender.append(matchId);
            appender.append(matchDate);
            appender.append(minutesBefore);
            appender.append(ratingBefore);
            appender.append(residual);
            appender.append(minutesPlayed);
            appender.append(ratingAfter);
            appender.endRow();
            rows++;
        } catch (SQLException e) {
            throw new IllegalStateException("rating history write failed after " + rows + " rows", e);
        }
    }

    public long rows() {
        return rows;
    }

    @Override
    public void close() throws SQLException {
        appender.close();
        connection.close();
    }
}
