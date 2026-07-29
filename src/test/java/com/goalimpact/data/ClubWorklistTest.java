package com.goalimpact.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The club-scoped read side (item 29, slice 2): the other door into the
// worklist. The player door can only offer a match some tier names a player
// for, which is 54% of them; a club door reaches all of them, because a match
// always knows its two clubs.
//
// DB-backed like WorklistReaderTest, and skipped when either file is absent.
// Every figure below was measured by SQL before the club queries existed.
class ClubWorklistTest {

    private static final Path RESULTS = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb");
    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // Spain: a national side, which the vendor models as a club, and exactly the
    // case the player door cannot reach - all 50 of its Held matches are
    // "no lineups" with only their own events left.
    //
    // THIS COUNT MOVES WHEN YOU REPAIR A SPAIN MATCH, and that is not a bug in
    // the test. It was 51 until the Euro 2024 final (game 4359342, Spain v
    // England) was released to close item 29 slice 2, which is the whole point of
    // the tool. A failure here after a repair means: check the new number is
    // exactly one lower per match released, then update it. The invariant that
    // cannot drift is asserted below - the search's count and the list's length
    // must agree, or the two halves of the screen contradict each other.
    private static final long SPAIN = 3375L;
    private static final int SPAIN_HELD = 50;

    // Juventus: 739 games in the snapshot and not one Held match. The zero is
    // the point - it says "this club is complete", which a search that only
    // offered clubs with work could never say.
    private static final long JUVENTUS = 506L;

    private static void assumeDatabases() {
        assumeTrue(Files.exists(RESULTS), "results database not present");
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
    }

    private static Optional<ClubHit> find(List<ClubHit> hits, long clubId) {
        return hits.stream().filter(h -> h.clubId() == clubId).findFirst();
    }

    @Test
    void searchingAClubReportsHowMuchWorkItHas() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            ClubHit spain = find(reader.searchClubs("spain"), SPAIN).orElseThrow();
            assertEquals("Spain", spain.clubName());
            assertEquals(SPAIN_HELD, spain.heldMatches());
        }
    }

    @Test
    void aCompleteClubIsFoundAndReportsZero() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            ClubHit juventus = find(reader.searchClubs("juventus"), JUVENTUS).orElseThrow();
            assertEquals(0, juventus.heldMatches());
        }
    }

    // The list a club opens on: every Held match it played on either side, most
    // recent first, each saying what is left to repair it from. The count must
    // be the very number the search promised, or the screen contradicts itself.
    @Test
    void aClubOpensOnItsHeldMatchesMostRecentFirst() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            List<ClubMatchRow> rows = reader.heldMatchesFor(SPAIN);
            assertEquals(SPAIN_HELD, rows.size());
            assertEquals(find(reader.searchClubs("spain"), SPAIN).orElseThrow().heldMatches(),
                rows.size());

            for (ClubMatchRow row : rows) {
                assertEquals(RepairSource.EVENTS, row.repairSource());
                assertEquals("no lineups", row.reason());
                assertTrue(row.match().homeName().equals("Spain")
                    || row.match().awayName().equals("Spain"), "Spain is not in this match");
            }
            for (int i = 1; i < rows.size(); i++) {
                assertFalse(rows.get(i).match().date().isAfter(rows.get(i - 1).match().date()),
                    "not ordered most recent first at row " + i);
            }
        }
    }

    @Test
    void aCompleteClubOpensOnAnEmptyList() throws Exception {
        assumeDatabases();
        try (WorklistReader reader = new WorklistReader(RESULTS, SNAPSHOT)) {
            assertTrue(reader.heldMatchesFor(JUVENTUS).isEmpty());
        }
    }
}
