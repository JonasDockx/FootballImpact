package com.goalimpact.engine;

import com.goalimpact.model.Competition;
import com.goalimpact.model.Match;
import com.goalimpact.model.Team;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubPoolsTest {

    private static final Competition GB1 = new Competition("GB1", Competition.Kind.LEAGUE);
    private static final Competition GB2 = new Competition("GB2", Competition.Kind.LEAGUE);
    private static final Competition L1 = new Competition("L1", Competition.Kind.LEAGUE);
    private static final Competition FAC = new Competition("FAC", Competition.Kind.OTHER);
    private static final Competition EURO = new Competition("EURO", Competition.Kind.NATIONAL_TEAM);

    private static Match match(Competition competition, long homeId, long awayId) {
        return new Match(1000 + homeId * 100 + awayId, competition, LocalDate.of(2024, 1, 1),
            new Team(homeId, "club " + homeId), new Team(awayId, "club " + awayId),
            0, 0, Match.HomeSide.HOME);
    }

    @Test
    void aClubThatNeverPlaysLeagueFootballIsUnpriced() {
        // The item 16 population: a fourth-tier side that exists in the run
        // only as cup opposition. Club 1 plays a league, club 9 never does.
        ClubPools pools = ClubPools.of(List.of(
            match(GB1, 1, 2),
            match(FAC, 1, 9)));

        assertFalse(pools.unpriced(1));
        assertFalse(pools.unpriced(2));
        assertTrue(pools.unpriced(9));
    }

    @Test
    void aNationalTeamIsNeverUnpriced() {
        // A national side plays no league football either, and reading that
        // as "unknown" would be exactly backwards - which is why the kind is
        // carried and not just league-or-not.
        ClubPools pools = ClubPools.of(List.of(
            match(EURO, 3299, 3375),
            match(GB1, 1, 2)));

        assertFalse(pools.unpriced(3299));
        assertFalse(pools.unpriced(3375));
    }

    @Test
    void aClubUnknownToTheRunIsNotUnpriced() {
        // Unpriced is a verdict about a club the run HAS seen. Anything else
        // is a question that was never asked, and answering it -X would seed
        // strangers off the back of a typo.
        ClubPools pools = ClubPools.of(List.of(match(GB1, 1, 2)));

        assertFalse(pools.unpriced(77));
    }

    @Test
    void twoClubsInDifferentLeaguesBridge() {
        ClubPools pools = ClubPools.of(List.of(
            match(GB1, 1, 2),
            match(L1, 3, 4)));

        assertTrue(pools.isBridge(match(FAC, 1, 3)));
    }

    @Test
    void twoClubsInTheSameLeagueDoNotBridge() {
        // The reason the gate is scoped at all (#39): a whole-league
        // mispricing moves both sides equally, so the gap barely moves and a
        // correct fix scores as a tie here.
        ClubPools pools = ClubPools.of(List.of(match(GB1, 1, 2)));

        assertFalse(pools.isBridge(match(GB1, 1, 2)));
    }

    @Test
    void aPricedClubAgainstAnUnpricedOneBridges() {
        // Item 16's own population: this is the match the seed moves.
        ClubPools pools = ClubPools.of(List.of(
            match(GB1, 1, 2),
            match(FAC, 1, 9)));

        assertTrue(pools.isBridge(match(FAC, 1, 9)));
    }

    @Test
    void twoUnpricedClubsDoNotBridge() {
        // Both sides get the same seed, so the gap is exactly what it was.
        // Grading these would dilute the gate with matches the fix cannot move.
        ClubPools pools = ClubPools.of(List.of(
            match(GB1, 1, 2),
            match(FAC, 8, 9)));

        assertFalse(pools.isBridge(match(FAC, 8, 9)));
    }

    @Test
    void aPromotedClubSharesBothItsLeagues() {
        // Club 2 plays GB2 one season and GB1 the next. Against a GB1 club it
        // is a domestic fixture, not a bridge; against a French club it is.
        ClubPools pools = ClubPools.of(List.of(
            match(GB2, 2, 5),
            match(GB1, 1, 2),
            match(L1, 3, 4)));

        assertFalse(pools.isBridge(match(FAC, 1, 2)));
        assertTrue(pools.isBridge(match(FAC, 2, 3)));
    }

    @Test
    void twoNationalTeamsDoNotBridge() {
        // Neither plays a league, so by the letter of "clubs from different
        // leagues" this is not one. The players inside DO come from different
        // leagues - CONTEXT's sense of Bridge - but no club-level seed moves
        // this prediction, so it is out of the item 16 gate.
        ClubPools pools = ClubPools.of(List.of(match(EURO, 3299, 3375)));

        assertFalse(pools.isBridge(match(EURO, 3299, 3375)));
    }

    @Test
    void countsTheRunItWasBuiltFrom() {
        ClubPools pools = ClubPools.of(List.of(
            match(GB1, 1, 2),
            match(FAC, 1, 9),
            match(EURO, 3299, 3375)));

        // Three clubs, not five: the two national sides are out of the
        // denominator for the same reason they are out of the numerator.
        assertEquals(3, pools.clubs());
        assertEquals(1, pools.unpricedClubs());
    }
}
