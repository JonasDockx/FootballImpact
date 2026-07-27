package com.goalimpact.repair;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The picker's order, on plain lists (item 17, slice 1, decision 10). Nothing
// here touches a database or a screen: the reader hands over evidence, this
// decides what order the names are offered in, and being wrong costs a keystroke
// (decision 1) - which is why the ranking may be aggressive.
class CandidateRankerTest {

    private static PlayerCandidate nearby(long id, String name, int nearbyMatches) {
        return new PlayerCandidate(id, name, "Midfield", null, nearbyMatches, true);
    }

    private static PlayerCandidate ever(long id, String name) {
        return new PlayerCandidate(id, name, "Midfield", null, 0, true);
    }

    private static PlayerCandidate stranger(long id, String name) {
        return new PlayerCandidate(id, name, "Midfield", null, 0, false);
    }

    private static List<Long> ids(List<RankedCandidate> ranked) {
        return ranked.stream().map(r -> r.candidate().playerId()).toList();
    }

    private static List<RankedCandidate> rank(List<PlayerCandidate> pool, String typed) {
        return CandidateRanker.rank(pool, typed, Map.of(), 50);
    }

    // Rank 0 is the club's nearby squad, most-seen first; ties fall back to the
    // name so the list never reshuffles between two equal players.
    @Test
    void rankZeroIsOrderedByNearbyMatchesThenName() {
        List<RankedCandidate> ranked = rank(List.of(
            nearby(3, "Zeeman", 2), nearby(1, "Aerts", 2), nearby(2, "Bosmans", 9)), "");

        assertEquals(List.of(2L, 1L, 3L), ids(ranked));
        assertTrue(ranked.stream().allMatch(r -> r.rank() == 0));
    }

    // Nothing but rank 0 shows until something is typed (decision 6): rank 2 alone
    // is 114,893 players, and an unfiltered rank 1 is every squad the club ever had.
    @Test
    void nothingButRankZeroShowsBeforeTyping() {
        List<RankedCandidate> ranked = rank(List.of(
            nearby(1, "Aerts", 3), ever(2, "Bosmans"), stranger(3, "Claes")), "");

        assertEquals(List.of(1L), ids(ranked));
    }

    // Typing reveals the other two rungs, in rung order behind the nearby squad.
    @Test
    void typingRevealsRankOneThenRankTwo() {
        List<RankedCandidate> ranked = rank(List.of(
            stranger(3, "Claes"), ever(2, "Claes"), nearby(1, "Claes", 1)), "cla");

        assertEquals(List.of(1L, 2L, 3L), ids(ranked));
        assertEquals(List.of(0, 1, 2), ranked.stream().map(RankedCandidate::rank).toList());
    }

    @Test
    void theTypedFilterAppliesAcrossAllThreeRanks() {
        List<RankedCandidate> ranked = rank(List.of(
            nearby(1, "Aerts", 5), ever(2, "Bosmans"), stranger(3, "Claes")), "os");

        assertEquals(List.of(2L), ids(ranked));
    }

    @Test
    void theFilterIsCaseInsensitiveAndMatchesAnywhereInTheName() {
        assertEquals(List.of(1L), ids(rank(List.of(nearby(1, "Jean-Luc Vandenbroeck", 1)),
            "BROECK")));
    }

    // The cap protects the screen from the everyone-else rung only; the club's own
    // squad is small and always shown whole.
    @Test
    void onlyRankTwoIsCapped() {
        List<PlayerCandidate> pool = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pool.add(nearby(100 + i, "Aerts " + i, 1));
            pool.add(stranger(200 + i, "Aerts " + i));
        }
        List<RankedCandidate> ranked = CandidateRanker.rank(pool, "aerts", Map.of(), 5);

        assertEquals(20, ranked.stream().filter(r -> r.rank() == 0).count());
        assertEquals(5, ranked.stream().filter(r -> r.rank() == 2).count());
    }

    // The three arms of the reader's query can each name the same man; the screen
    // must show him once, at the best rank any arm justifies.
    @Test
    void theSameManFromTwoArmsIsMergedAtHisStrongestEvidence() {
        List<RankedCandidate> ranked = rank(List.of(
            stranger(7, "Dupont"), nearby(7, "Dupont", 4)), "dup");

        assertEquals(1, ranked.size());
        assertEquals(0, ranked.get(0).rank());
        assertEquals(4, ranked.get(0).candidate().nearbyMatches());
    }

    // Merging keeps whatever either arm knew: the vendor players row carries the
    // date of birth, the club arm carries the nearby count.
    @Test
    void mergingKeepsTheFactsEitherArmHeld() {
        PlayerCandidate withDob = new PlayerCandidate(7, "Dupont", "Unknown",
            java.time.LocalDate.of(1975, 3, 1), 0, false);
        List<RankedCandidate> ranked = rank(List.of(withDob, nearby(7, "Dupont", 2)), "dup");

        assertEquals(java.time.LocalDate.of(1975, 3, 1), ranked.get(0).candidate().dateOfBirth());
        assertEquals("Midfield", ranked.get(0).candidate().position());
    }

    // A player already in the match stays on the list, greyed with the reason
    // (decision 7) - hiding him would read as "not found" and invite a duplicate
    // creation, which is the one failure ADR 0012 exists to prevent.
    @Test
    void aPlayerAlreadyInTheMatchIsShownWithHisReason() {
        List<RankedCandidate> ranked = CandidateRanker.rank(
            List.of(nearby(1, "Aerts", 3), nearby(2, "Bosmans", 2)), "",
            Map.of(1L, "already in Lierse's XI"), 50);

        assertEquals(List.of(1L, 2L), ids(ranked));
        assertEquals("already in Lierse's XI", ranked.get(0).alreadyIn());
        assertTrue(ranked.get(0).unavailable());
        assertNull(ranked.get(1).alreadyIn());
        assertFalse(ranked.get(1).unavailable());
    }
}
