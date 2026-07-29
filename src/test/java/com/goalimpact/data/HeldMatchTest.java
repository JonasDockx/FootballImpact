package com.goalimpact.data;

import com.goalimpact.model.Match;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The match-level spine of the worklist (item 29, slice 1): one row per Held
// match, whatever the reason, carrying both club ids and the Repair source. It
// exists because a worklist built only from player rows can never reach a match
// nobody names - which cost 46% of the Held set (item 29).
//
// DB-backed like HeldAppearanceTest and MissingMatchTest, and skipped when the
// snapshot is absent. Every figure below was measured by SQL before a line of
// heldMatches() existed, so the loader is not grading its own homework.
class HeldMatchTest {

    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // Replay a whole competition-season, skips and all; the gate records each
    // Held match before it throws.
    private static void replay(TransfermarktLoader loader, String competitionId, String season)
        throws Exception {

        for (Match m : loader.loadMatches(competitionId, season)) {
            try {
                loader.loadEvents(m);
            } catch (UnusableMatchException ignored) {
                // the gate records the Held match before it throws
            }
        }
    }

    private static HeldMatch one(List<HeldMatch> held, long gameId) {
        return held.stream().filter(h -> h.gameId() == gameId).findFirst().orElseThrow();
    }

    // DFB-Pokal 2012 is the pre-team-sheet season: all 63 games are Held for
    // "no lineups", and game 2221641 (Bayern-Hannover) is clubs 1203 and 42.
    @Test
    void aHeldMatchIsRecordedWithBothItsClubs() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            replay(loader, "DFB", "2012");
            List<HeldMatch> held = loader.heldMatches();
            assertEquals(63, held.size());

            HeldMatch bayern = one(held, 2221641L);
            assertEquals(1203L, bayern.homeClubId());
            assertEquals(42L, bayern.awayClubId());
            assertEquals("no lineups", bayern.reason());
        }
    }

    // Not one of the 63 has a team sheet, so the Repair source is decided
    // entirely below it: 49 of them still carry an appearances record, and the
    // other 14 have only their own events to go on. Measured by SQL first.
    @Test
    void theRepairSourceIsTheBestRecordTheMatchStillHas() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            replay(loader, "DFB", "2012");
            List<HeldMatch> held = loader.heldMatches();

            assertEquals(0, count(held, RepairSource.TEAM_SHEET));
            assertEquals(49, count(held, RepairSource.APPEARANCES));
            assertEquals(14, count(held, RepairSource.EVENTS));
            assertEquals(0, count(held, RepairSource.NOTHING));

            assertEquals(RepairSource.APPEARANCES, one(held, 2221641L).repairSource());
        }
    }

    // A broken team sheet is still a team sheet: game 3906312 (DKP 2022) was
    // rejected for naming two starting goalkeepers, not for silence, so 34
    // lineup rows are sitting there to repair from. The events it also carries
    // do not lower it a rung.
    @Test
    void aBrokenTeamSheetIsStillTheRichestSource() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            replay(loader, "DKP", "2022");
            HeldMatch match = one(loader.heldMatches(), 3906312L);
            assertEquals(67612L, match.homeClubId());
            assertEquals(24193L, match.awayClubId());
            assertEquals("two starting goalkeepers", match.reason());
            assertEquals(RepairSource.TEAM_SHEET, match.repairSource());
        }
    }

    // The bottom rung, and the one the repair GUI must refuse to open: UKR1 2013
    // carries exactly two games with no team sheet, no appearances and no events
    // of their own. They are still Held and still reachable - both club ids are
    // there - which is the whole point of listing a match nobody names.
    @Test
    void aMatchWithNoSurvivingRecordIsStillListedWithItsClubs() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            replay(loader, "UKR1", "2013");
            List<HeldMatch> held = loader.heldMatches();
            assertEquals(2, count(held, RepairSource.NOTHING));

            HeldMatch match = one(held, 2335710L);
            assertEquals(RepairSource.NOTHING, match.repairSource());
            assertEquals(2227L, match.homeClubId());
            assertEquals(9007L, match.awayClubId());
            assertEquals(RepairSource.NOTHING, one(held, 2453109L).repairSource());
        }
    }

    // The spine holds decisions the gate made, never a census of the snapshot:
    // a match that replays is not Held and must not be listed as work.
    @Test
    void aCleanMatchIsNotHeld() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            loader.loadEvents(loader.loadMatches("GB1", "2024").get(0));   // the clean opener
            assertTrue(loader.heldMatches().isEmpty());
        }
    }

    private static long count(List<HeldMatch> held, RepairSource source) {
        return held.stream().filter(h -> h.repairSource() == source).count();
    }
}
