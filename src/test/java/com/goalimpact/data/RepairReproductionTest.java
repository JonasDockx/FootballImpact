package com.goalimpact.data;

import com.goalimpact.repair.EditableMatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Gate check 1: the writer reproduces a repair already known to work. Game
// 2501210 was fixed by hand in scripts/first-repair.sql at stage 3 - the vendor
// tagged goalkeeper Luke Steele (3539) as Centre-Forward, leaving Panathinaikos
// with no starting keeper. Here the editor rebuilds it the way the GUI will:
// load from the vendor, retag the keeper, save released - into a throwaway
// sidecar. Every column of all four tables must then match the real sidecar's
// rows except provenance. No external evidence is needed and the precious file
// is only read.
class RepairReproductionTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;
    private static final Path REAL_SIDECAR = DataFiles.SIDECAR;
    private static final long GAME = 2501210L;
    private static final long KEEPER = 3539L;

    @TempDir
    Path tempDir;

    @Test
    void rebuildsGame2501210ByteForByteExceptProvenance() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        assumeTrue(Files.exists(REAL_SIDECAR), "sidecar not present");

        Path made = tempDir.resolve("reproduction.duckdb");
        SidecarStore store = new SidecarStore(made, SNAPSHOT);

        EditableMatch vendor = store.load(GAME);
        assertEquals(List.of("no starting goalkeeper"), vendor.problems(),
            "the vendor copy should show the stage-3 defect");

        EditableMatch fixed = vendor.withPosition(KEEPER, "Goalkeeper");
        assertTrue(fixed.problems().isEmpty(), "retagging the keeper should clear the gate");

        store.save(fixed, "released", fixed.provenanceSummary());

        assertNoDifference(made);
    }

    // Attach both sidecars read-only into a scratch in-memory database and take
    // the symmetric multiset difference of each table (EXCEPT ALL both ways,
    // scoped to this game). matches drops provenance - the one column meant to
    // differ - and compares everything else, commit_hash included.
    private void assertNoDifference(Path made) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
            Statement s = c.createStatement()) {
            s.execute("ATTACH '" + forward(made) + "' AS made (READ_ONLY)");
            s.execute("ATTACH '" + forward(REAL_SIDECAR) + "' AS gold (READ_ONLY)");

            String matchCols = "game_id, status, date, competition_id, season, round, "
                + "competition_type, home_club_id, home_club_name, away_club_id, "
                + "away_club_name, home_club_goals, away_club_goals, commit_hash";
            assertSame(s, "matches", matchCols);
            assertSame(s, "game_lineups", "*");
            assertSame(s, "game_events", "*");
            assertSame(s, "appearances", "*");
        }
    }

    private static void assertSame(Statement s, String table, String cols) throws Exception {
        assertEquals(0, differing(s, "made." + table, "gold." + table, cols),
            "rows in made." + table + " not matched in gold." + table);
        assertEquals(0, differing(s, "gold." + table, "made." + table, cols),
            "rows in gold." + table + " not matched in made." + table);
    }

    private static long differing(Statement s, String from, String to, String cols)
        throws Exception {
        String sql = "SELECT count(*) FROM ("
            + "SELECT " + cols + " FROM " + from + " WHERE game_id = " + GAME
            + " EXCEPT ALL "
            + "SELECT " + cols + " FROM " + to + " WHERE game_id = " + GAME + ")";
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String forward(Path p) {
        return p.toString().replace('\\', '/');
    }
}
