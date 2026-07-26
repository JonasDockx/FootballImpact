package com.goalimpact.repair;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineupEntryTest {

    private static LineupEntry row(String position, String type) {
        return new LineupEntry(1L, 3539L, "Luke Steele", position, type);
    }

    @Test
    void starterIsExactlyTheStartingLineupType() {
        assertTrue(row("Goalkeeper", "starting_lineup").starter());
        assertFalse(row("Goalkeeper", "substitutes").starter());
    }

    @Test
    void goalkeeperIsExactlyTheGoalkeeperPosition() {
        assertTrue(row("Goalkeeper", "starting_lineup").goalkeeper());
        assertFalse(row("Centre-Forward", "starting_lineup").goalkeeper());
    }

    @Test
    void aBenchedKeeperIsStillAGoalkeeperButNotAStarter() {
        LineupEntry benched = row("Goalkeeper", "substitutes");
        assertTrue(benched.goalkeeper());
        assertFalse(benched.starter());
    }

    @Test
    void withPositionRetagsAndKeepsEverythingElse() {
        LineupEntry fixed = row("Centre-Forward", "starting_lineup").withPosition("Goalkeeper");
        assertEquals("Goalkeeper", fixed.position());
        assertEquals("starting_lineup", fixed.type());
        assertEquals(3539L, fixed.playerId());
        assertTrue(fixed.goalkeeper());
    }

    @Test
    void benchAndStartFlipOnlyTheType() {
        LineupEntry starter = row("Goalkeeper", "starting_lineup");
        assertEquals("substitutes", starter.asBench().type());
        assertEquals("Goalkeeper", starter.asBench().position());
        assertEquals("starting_lineup", starter.asBench().asStarter().type());
    }
}
