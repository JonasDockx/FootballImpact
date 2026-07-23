package com.goalimpact.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aCareerSplitsIntoItsThreeRungs() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            Worklist w = reader.worklistFor(VERTONGHEN);
            assertEquals(VERTONGHEN, w.playerId());
            assertEquals(1, w.certain().size());
            assertEquals(47, w.appeared().size());
            assertEquals(12, w.maybe().size());
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

    @Test
    void everyRungIsOrderedMostRecentFirst() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            Worklist w = reader.worklistFor(VERTONGHEN);
            AppearedRow appeared = w.appeared().get(0);
            assertEquals(2225743L, appeared.match().gameId());   // 2013-05-19
            assertEquals(148L, appeared.clubId());               // Tottenham
            assertEquals(90, appeared.minutes());
            MaybeRow maybe = w.maybe().get(0);
            assertEquals(4057566L, maybe.match().gameId());      // 2023-04-20
            assertEquals(58L, maybe.clubId());                   // Anderlecht
            assertEquals(4, maybe.nearbyMatches());
        }
    }

    @Test
    void searchFindsAPlayerByPartOfHisName() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            List<WorklistPlayer> hits = reader.searchPlayers("vertonghen");
            assertEquals(1, hits.size());
            assertEquals(VERTONGHEN, hits.get(0).playerId());
            assertEquals(60, hits.get(0).missingMatches());       // 1 + 47 + 12
        }
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
