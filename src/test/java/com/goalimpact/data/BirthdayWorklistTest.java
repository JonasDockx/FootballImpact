package com.goalimpact.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The third door (#53, stage 1 of #45): every rated player with no date of
// birth, ranked by career minutes. Nothing here writes - stage 1 lands the read
// path inert - so this test only ever opens files read-only.
//
// DB-backed like ClubWorklistTest, and skipped when either file is absent.
// Every figure below was measured by SQL against the designated run of
// 2026-08-04 before the query existed, and reproduces the counts recorded on
// #52: 55,185 rated men with no date, 2,983 past 1,000 minutes, 28 past 5,000,
// 1 past 10,000, and 28 of the 55,185 holding a vendor `players` row.
class BirthdayWorklistTest {

    private static final Path RESULTS = DataFiles.RESULTS;
    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    // THESE COUNTS MOVE WHEN A DESIGNATED RUN IS RE-RUN over a refreshed
    // snapshot, and that is not a bug in the test - it is the population
    // changing under it. A failure here means: re-measure, check the new number
    // is explained by what was ingested, then update it. What cannot drift is
    // asserted structurally below - the ordering, and that every row really is
    // missing a vendor date.
    private static final int WITHOUT_A_DATE = 55_185;
    private static final int PAST_1000_MINUTES = 2_983;
    private static final int PAST_5000_MINUTES = 28;
    private static final int WITH_A_VENDOR_ROW = 28;

    // The one man past 10,000 minutes, and so the top of the list.
    private static final long OKAZAKI = 79642L;

    private static void assumeDatabases() {
        assumeTrue(Files.exists(RESULTS), "results database not present");
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
    }

    // The whole population, not a page of it: #52 decision 2 inverted
    // WorklistPane's contract on purpose, so the list IS the answer and the
    // search box only narrows it. A capped or thresholded query would hide the
    // very rows a search is for.
    @Test
    void theListIsTheWholePopulationRankedByMinutes() throws Exception {
        assumeDatabases();
        try (BirthdayReader reader = new BirthdayReader(RESULTS, SNAPSHOT)) {
            List<BirthdayRow> rows = reader.rankedByMinutes();

            assertEquals(WITHOUT_A_DATE, rows.size());
            assertEquals(OKAZAKI, rows.get(0).playerId());
            assertEquals(PAST_1000_MINUTES,
                rows.stream().filter(r -> r.minutes() >= 1000).count());
            assertEquals(PAST_5000_MINUTES,
                rows.stream().filter(r -> r.minutes() >= 5000).count());

            for (int i = 1; i < rows.size(); i++) {
                assertFalse(rows.get(i).minutes() > rows.get(i - 1).minutes(),
                    "not ranked by minutes at row " + i);
            }
        }
    }

    // Ranking by minutes is the whole design (#45), so the head must be a short
    // morning's work and the tail must be the thing you deliberately abandon.
    @Test
    void theHeadIsSmallEnoughToBeWorthTyping() throws Exception {
        assumeDatabases();
        try (BirthdayReader reader = new BirthdayReader(RESULTS, SNAPSHOT)) {
            List<BirthdayRow> rows = reader.rankedByMinutes();
            assertTrue(rows.get(PAST_5000_MINUTES - 1).minutes() >= 5000);
            assertTrue(rows.get(PAST_5000_MINUTES).minutes() < 5000);
        }
    }

    // Both populations in one list (#52 decision 3), told apart by a column
    // rather than by a tab: they are one question and, after #51, one write.
    // The 2,968 with no `players` row at all are the men #30's widened era
    // brought in - Salzburg, Qarabag, Ludogorets - and they are the top of the
    // list, not its tail.
    @Test
    void bothRecordsAreListedAndDistinguishable() throws Exception {
        assumeDatabases();
        try (BirthdayReader reader = new BirthdayReader(RESULTS, SNAPSHOT)) {
            List<BirthdayRow> rows = reader.rankedByMinutes();

            assertEquals(WITH_A_VENDOR_ROW,
                rows.stream().filter(BirthdayRow::hasVendorRow).count());
            assertTrue(rows.stream().anyMatch(r -> !r.hasVendorRow()),
                "nobody in the list is missing a players row");
            assertTrue(rows.get(0).hasVendorRow(), "Okazaki has a vendor row with an empty date");
        }
    }

    // What you actually type into a search engine to find a man's birthday, so
    // the row carries it and no second screen is needed (#52 decision 4).
    @Test
    void everyRowCarriesTheEvidenceYouWouldSearchHimBy() throws Exception {
        assumeDatabases();
        try (BirthdayReader reader = new BirthdayReader(RESULTS, SNAPSHOT)) {
            BirthdayRow top = reader.rankedByMinutes().get(0);

            assertEquals("Shinji Okazaki", top.name());
            assertEquals(17_593, top.minutes());
            assertEquals(300, top.appearances());
            assertEquals(2012, top.firstYear());
            assertEquals(2023, top.lastYear());
            assertEquals("Leicester City", top.mainClub());
            assertEquals(5, top.clubs());
        }
    }

    // The trap that broke the tab on its first launch: DuckDB keeps ONE database
    // instance per file per JVM, so an ATTACH is shared by every connection to
    // that file rather than being per-connection. GuiMain opens a WorklistReader
    // and a BirthdayReader over the same results file, and the second one's
    // ATTACH ... AS vendor failed with "database with name vendor already
    // exists" - a whole tab replaced by an error message, with every test still
    // green because no test had ever built both.
    @Test
    void aSecondReaderOverTheSameFileStillOpens() throws Exception {
        assumeDatabases();
        try (WorklistReader first = new WorklistReader(RESULTS, SNAPSHOT);
            BirthdayReader second = new BirthdayReader(RESULTS, SNAPSHOT)) {
            assertNotNull(first.runId());
            assertFalse(second.rankedByMinutes().isEmpty());
        }
    }

    // Built either way round, because GuiMain's construction order is not a
    // contract and the two readers must not quietly depend on it.
    @Test
    void andSoDoesTheOtherOrder() throws Exception {
        assumeDatabases();
        try (BirthdayReader first = new BirthdayReader(RESULTS, SNAPSHOT);
            WorklistReader second = new WorklistReader(RESULTS, SNAPSHOT)) {
            assertFalse(first.rankedByMinutes().isEmpty());
            assertNotNull(second.runId());
        }
    }

    // The same trap, one turn worse, and the reason this reader does not open
    // the sidecar at all. DuckDB refuses to OPEN a file that is attached
    // elsewhere in the JVM ("Unique file handle conflict"), so an earlier draft
    // that attached the sidecar for one display column broke every sidecar write
    // for as long as the tab existed - including the repair tool's own saves,
    // from a tab that has nothing to do with repairs. Nothing would have caught
    // it: the write tests use a @TempDir sidecar and never build this reader.
    @Test
    void theSidecarIsStillWritableWhileTheListIsOpen(@TempDir Path tempDir) throws Exception {
        assumeDatabases();
        Path sidecar = tempDir.resolve("sidecar.duckdb");
        SidecarStore store = new SidecarStore(sidecar, SNAPSHOT);
        store.setBirthDate(OKAZAKI, LocalDate.of(1986, 4, 16), "before");

        try (BirthdayReader reader = new BirthdayReader(RESULTS, SNAPSHOT)) {
            reader.rankedByMinutes();
            store.setBirthDate(OKAZAKI, LocalDate.of(1986, 4, 15), "while the list is open");
            assertEquals(LocalDate.of(1986, 4, 15),
                store.typedBirthDates().get(OKAZAKI).dateOfBirth());
        }
    }

    // Every id in the list must be nameable, or a row is a number the operator
    // cannot search on. 142 rated ids carry no name anywhere, so the query falls
    // back to a label rather than a null (the loader's own convention).
    @Test
    void everyRowIsNamed() throws Exception {
        assumeDatabases();
        try (BirthdayReader reader = new BirthdayReader(RESULTS, SNAPSHOT)) {
            for (BirthdayRow row : reader.rankedByMinutes()) {
                assertNotNull(row.name(), "player " + row.playerId() + " has no name");
                assertFalse(row.name().isBlank(), "player " + row.playerId() + " has a blank name");
            }
        }
    }
}
