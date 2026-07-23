package com.goalimpact.data;

import com.goalimpact.model.Match;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HeldAppearanceTest {

    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // Replay a whole competition-season (skips and all), then return the
    // captured worklist rows for one game id.
    private static List<HeldAppearance> heldFor(TransfermarktLoader loader,
        String competitionId, String season, long gameId) throws Exception {

        for (Match m : loader.loadMatches(competitionId, season)) {
            try {
                loader.loadEvents(m);
            } catch (UnusableMatchException ignored) {
                // the gate records the worklist row before it throws
            }
        }
        return loader.heldAppearances().stream()
            .filter(h -> h.gameId() == gameId).toList();
    }

    @Test
    void aTwoGoalkeeperMatchNamesEveryPlayerInItsLineup() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<HeldAppearance> held = heldFor(loader, "DKP", "2022", 3906312L);
            assertEquals(34, held.size());
            assertTrue(held.stream().allMatch(h -> h.reason().equals("two starting goalkeepers")));
            assertEquals(22, held.stream().filter(HeldAppearance::started).count());
        }
    }

    @Test
    void aNoGoalkeeperMatchNamesEveryPlayerInItsLineup() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<HeldAppearance> held = heldFor(loader, "NLP", "2017", 2904555L);
            assertEquals(34, held.size());
            assertTrue(held.stream().allMatch(h -> h.reason().equals("no starting goalkeeper")));
            assertEquals(22, held.stream().filter(HeldAppearance::started).count());
        }
    }

    @Test
    void aShortStartingXiMatchNamesEveryPlayerInItsLineup() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<HeldAppearance> held = heldFor(loader, "SFA", "2025", 4808142L);
            assertEquals(37, held.size());
            assertTrue(held.stream().allMatch(h -> h.reason().equals("XI is not 11")));
            assertEquals(21, held.stream().filter(HeldAppearance::started).count());
        }
    }

    @Test
    void aCleanMatchNamesNobody() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            loader.loadEvents(loader.loadMatches("GB1", "2024").get(0));   // the clean opener
            assertTrue(loader.heldAppearances().isEmpty());
        }
    }
}
