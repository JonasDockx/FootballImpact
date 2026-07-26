package com.goalimpact.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The read side of the per-player worklist (item 26, stage 4b-1): the three rungs
// out of the results DB with the vendor's match facts joined on. Every figure
// below was measured by SQL before a line of WorklistReader existed, which is
// what stops the reader grading its own homework. Skipped when either database
// is absent, like MissingMatchTest. Note both files are needed: the worklist
// lives in the disposable results DB, the match facts in the vendor snapshot.
class WorklistReaderTest {

    private static final Path RESULTS = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb");
    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // Jan Vertonghen: one career touching all three rungs, which is rare enough
    // to be a real test - Benfica 2021 (broken team sheet), Spurs 2012/13 (the
    // pre-team-sheet season, named by appearances), Anderlecht 2023 (a guess).
    private static final long VERTONGHEN = 43250L;

    private static void assumeDatabases() {
        assumeTrue(Files.exists(RESULTS), "results database not present");
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
    }

    // The reader's split must match an independent count of the raw worklist
    // tables - a real cross-check, since the reader joins to vendor.games and could
    // in principle drop a row the count keeps. Absolute figures are not pinned: the
    // appeared rung shrinks every time clean matches are released (stage 4b-4), so
    // this asserts agreement with SQL, not yesterday's numbers. Vertonghen still
    // touches all three rungs - his remaining appeared games are the imperfect ones
    // a clean-only release leaves behind.
    @Test
    void aCareerSplitsIntoItsThreeRungs() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            Worklist w = reader.worklistFor(VERTONGHEN);
            assertEquals(VERTONGHEN, w.playerId());
            assertEquals(rawCount("held_appearances", VERTONGHEN), w.certain().size());
            assertEquals(rawCount("appeared_players", VERTONGHEN), w.appeared().size());
            assertEquals(rawCount("maybe_players", VERTONGHEN), w.maybe().size());
            assertTrue(w.certain().size() >= 1, "still a broken team sheet");
            assertTrue(w.appeared().size() >= 1, "still some appeared rows");
            assertTrue(w.maybe().size() >= 1, "still some maybe rows");
        }
    }

    @Test
    void aCertainRowCarriesTheVendorsMatchFacts() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            CertainRow row = reader.worklistFor(VERTONGHEN).certain().get(0);
            assertEquals(new MatchFacts(3621442L, LocalDate.of(2021, 11, 27),
                "PO1", "12. Matchday", "B SAD", "SL Benfica"), row.match());
            assertEquals(294L, row.clubId());          // Benfica
            assertTrue(row.started());
            assertEquals("XI is not 11", row.reason());
        }
    }

    // Ordering is asserted structurally - each rung's dates non-increasing - rather
    // than against a golden top row, which a release can retire. The first appeared
    // and maybe rows' fields are cross-checked against the raw worklist row for
    // whatever game now leads, so the field mapping is still verified without
    // pinning an id.
    @Test
    void everyRungIsOrderedMostRecentFirst() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            Worklist w = reader.worklistFor(VERTONGHEN);
            assertDatesDescending(w.appeared().stream().map(r -> r.match().date()).toList());
            assertDatesDescending(w.maybe().stream().map(r -> r.match().date()).toList());

            AppearedRow appeared = w.appeared().get(0);
            long[] rawAppeared = rawTwo("appeared_players", "club_id", "minutes",
                VERTONGHEN, appeared.match().gameId());
            assertEquals(rawAppeared[0], appeared.clubId());
            assertEquals(rawAppeared[1], appeared.minutes());

            MaybeRow maybe = w.maybe().get(0);
            long[] rawMaybe = rawTwo("maybe_players", "club_id", "nearby_matches",
                VERTONGHEN, maybe.match().gameId());
            assertEquals(rawMaybe[0], maybe.clubId());
            assertEquals(rawMaybe[1], maybe.nearbyMatches());
        }
    }

    @Test
    void searchFindsAPlayerByPartOfHisName() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            List<WorklistPlayer> hits = reader.searchPlayers("vertonghen");
            assertEquals(1, hits.size());
            assertEquals(VERTONGHEN, hits.get(0).playerId());
            Worklist w = reader.worklistFor(VERTONGHEN);
            int total = w.certain().size() + w.appeared().size() + w.maybe().size();
            assertEquals(total, hits.get(0).missingMatches());   // the three rungs summed
        }
    }

    private static void assertDatesDescending(List<LocalDate> dates) {
        for (int i = 1; i < dates.size(); i++) {
            assertFalse(dates.get(i).isAfter(dates.get(i - 1)),
                "rung is not ordered most recent first at row " + i);
        }
    }

    // An independent count of a raw worklist table, so the reader is checked against
    // SQL rather than against a figure that a release can change.
    private static long rawCount(String table, long playerId) throws Exception {
        try (Connection c = openResults();
            PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // Two numeric columns of one raw worklist row, for cross-checking the reader's
    // field mapping against whatever game now leads the rung.
    private static long[] rawTwo(String table, String colA, String colB,
        long playerId, long gameId) throws Exception {
        try (Connection c = openResults();
            PreparedStatement ps = c.prepareStatement("SELECT " + colA + ", " + colB
                + " FROM " + table + " WHERE player_id = ? AND game_id = ?")) {
            ps.setLong(1, playerId);
            ps.setLong(2, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[] { rs.getLong(1), rs.getLong(2) };
            }
        }
    }

    private static Connection openResults() throws Exception {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + RESULTS, readOnly);
    }

    // The known cost of searching the worklist's own names rather than the vendor
    // players table: a miss cannot tell a complete career from a typo. It must at
    // least be a quiet miss, not an exception, so the screen can say which it is.
    @Test
    void aNonsenseSearchIsEmptyRatherThanAnError() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            assertTrue(reader.searchPlayers("zzzznotafootballer").isEmpty());
        }
    }
}
