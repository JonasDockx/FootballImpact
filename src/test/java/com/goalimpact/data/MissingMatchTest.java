package com.goalimpact.data;

import com.goalimpact.model.Match;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The appeared and maybe tiers (item 26, stage 4a): the two lower rungs of the
// worklist, filled by joining appearances onto the "no lineups" Held matches the
// gate throws. DB-backed like HeldAppearanceTest, and skipped when the snapshot
// is absent.
class MissingMatchTest {

    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // Replay a whole competition-season so the gate records its no-lineup
    // matches; the appeared/maybe join is then read from the loader.
    private static void replay(TransfermarktLoader loader, String competitionId, String season)
        throws Exception {

        for (Match m : loader.loadMatches(competitionId, season)) {
            try {
                loader.loadEvents(m);
            } catch (UnusableMatchException ignored) {
                // the gate records the no-lineup match before it throws
            }
        }
    }

    @Test
    void appearedTierNamesEveryAppearanceOfANoLineupMatch() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        // DFB-Pokal 2012 is the pre-lineup season: no team sheets, but game
        // 2221641 (Bayern-Hannover) carries 14 appearances for Hannover (club 42).
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            replay(loader, "DFB", "2012");
            List<AppearedPlayer> rows = loader.appearedPlayers().stream()
                .filter(a -> a.gameId() == 2221641L).toList();
            assertEquals(14, rows.size());
            assertTrue(rows.stream().allMatch(a -> a.clubId() == 42L));
            assertTrue(rows.stream().allMatch(a -> a.playerName() != null));
        }
    }

    @Test
    void maybeTierListsCandidatesFromTheClubsSquadThatMonth() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        // Eliteserien 2024 game 4522747 has neither team sheet nor appearances;
        // its squad is guessed from the two clubs' (1100, 501) nearby matches.
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            replay(loader, "NO1", "2024");
            List<MaybePlayer> rows = loader.maybePlayers().stream()
                .filter(m -> m.gameId() == 4522747L).toList();
            assertEquals(37, rows.size());
            assertTrue(rows.stream().allMatch(m -> m.clubId() == 1100L || m.clubId() == 501L));
            assertTrue(rows.stream().allMatch(m -> m.nearbyMatches() >= 1));
        }
    }

    @Test
    void aCleanSeasonHasNoMissingMatchRows() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            loader.loadEvents(loader.loadMatches("GB1", "2024").get(0));   // the clean opener
            assertEquals(0, loader.heldNoLineupCount());
            assertTrue(loader.appearedPlayers().isEmpty());
            assertTrue(loader.maybePlayers().isEmpty());
        }
    }
}
