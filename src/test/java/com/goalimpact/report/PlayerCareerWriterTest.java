package com.goalimpact.report;

import com.goalimpact.engine.PlayerTally;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The career tag reaches the results file, which is the whole reason this
// writer exists: the viewer must be told who kept goal by the run that rated
// him, never by re-reading the vendor's lineups (ADR 0016).
class PlayerCareerWriterTest {

    @TempDir
    private Path dir;

    private static final Team INTER = new Team(8, "Internazionale");

    @Test
    void writesOneRowPerRatedPlayerWithHisCareerTag() throws Exception {
        Path results = dir.resolve("results.duckdb");

        long rows = PlayerCareerWriter.write(results, "TEST-RUN-k1.00",
            List.of(tally(1, "Nicolò Barella", false), tally(2, "Yann Sommer", true)));

        assertEquals(2, rows);
        assertFalse(goalkeeper(results, 1), "never started in goal");
        assertTrue(goalkeeper(results, 2), "started in goal");
        assertEquals("TEST-RUN-k1.00", runId(results));
    }

    // Rebuilt whole by every designated run, like the history beside it: a tag
    // is only true of the run that stamped it, so a second run must leave no
    // trace of the first.
    @Test
    void isRewrittenWholeByTheNextRun() throws Exception {
        Path results = dir.resolve("results.duckdb");
        PlayerCareerWriter.write(results, "OLD-RUN", List.of(tally(1, "A Reserve", true)));

        PlayerCareerWriter.write(results, "NEW-RUN", List.of(tally(1, "A Reserve", false)));

        assertEquals(1, count(results));
        assertFalse(goalkeeper(results, 1));
        assertEquals("NEW-RUN", runId(results));
    }

    private static PlayerTally tally(long id, String name, boolean keptGoal) {
        PlayerTally tally = new PlayerTally(new Player(id, name), INTER);
        if (keptGoal) {
            tally.startedInGoal();
        }
        return tally;
    }

    private static boolean goalkeeper(Path results, long playerId) throws SQLException {
        return one(results,
            "SELECT goalkeeper FROM player_careers WHERE player_id = " + playerId).equals("true");
    }

    private static String runId(Path results) throws SQLException {
        return one(results, "SELECT any_value(run_id) FROM player_careers");
    }

    private static int count(Path results) throws SQLException {
        return Integer.parseInt(one(results, "SELECT count(*) FROM player_careers"));
    }

    private static String one(Path results, String query) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + results);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return String.valueOf(rs.getObject(1));
        }
    }
}
