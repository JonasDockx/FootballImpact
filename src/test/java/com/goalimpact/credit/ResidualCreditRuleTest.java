package com.goalimpact.credit;

import com.goalimpact.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidualCreditRuleTest {
    
    private static final RatingLookup ALL_ZERO = playerId -> 0.0;

    private final ResidualSource rule = new ResidualCreditRule(new LogisticLinkFunction(1.0));

    // Players 1-11 are the scoring side, 12-23 are the conceding side, in all tests
    private static Player player(long id) {
        return new Player(id, "Player " + id);
    }

    private static Set<Player> players(long... ids) {
        Set<Player> set = new HashSet<>();
        for (long id : ids) {
            set.add(player(id));
        }
        return set;
    }

    @Test
    void equalStrengthGoalIsWorthHalf() {
        Map<Player, Double> deltas = rule.goal(players(1, 2, 3), players(12, 13, 14), ALL_ZERO);

        for (long id : new long[] {1, 2, 3}) {
            assertEquals(0.5, deltas.get(player(id)), 1e-9);
        }
        for (long id : new long[] {12, 13, 14}) {
            assertEquals(-0.5, deltas.get(player(id)), 1e-9);
        }
    }

    @Test
    void everyOnPitchPlayerGetsADelta() {
        Map<Player, Double> deltas = rule.goal(players(1, 2, 3), players(12, 13, 14), ALL_ZERO);

        assertEquals(6, deltas.size());
    }

    @Test
    void expectedGoalEarnsLittle() {
        // Scoring side much stronger: their goal was expected, so little credit.
        RatingLookup ratings = id -> id < 12 ? 3.0 : 0.0;

        Map<Player, Double> deltas = rule.goal(players(1, 2, 3), players(12, 13, 14), ratings);

        double credit = deltas.get(player(1));
        assertTrue(credit > 0);
        assertTrue(credit < 0.5);
    }

    @Test
    void upsetEarnsALot() {
        // Conceding side much stronger: scoring against them is an upset.
        RatingLookup ratings = id -> id < 12 ? 0.0 : 3.0;

        Map<Player, Double> deltas = rule.goal(players(1, 2, 3), players(12, 13, 14), ratings);

        double credit = deltas.get(player(1));
        assertTrue(credit > 0.5);
        assertTrue(credit < 1.0);
    }

    @Test
    void exactValueForKnownGap() {
        // Scoring avg 1.0, conceding avg 0.0, k=1: P = 0.7310585786300049,
        // so credit = 1 - P
        RatingLookup ratings = id -> id < 12 ? 1.0 : 0.0;

        Map<Player, Double> deltas = rule.goal(players(1, 2, 3), players(12, 13, 14), ratings);

        assertEquals(0.2689414213699951, deltas.get(player(1)), 1e-9);
        assertEquals(-0.2689414213699951, deltas.get(player(12)), 1e-9);        
    }

    @Test
    void creditAndBlameMirrorEachOther() {
        RatingLookup ratings = id -> id < 12 ? 2.0 : 0.5;

        Map<Player, Double> deltas = rule.goal(players(1, 2, 3), players(12, 13, 14), ratings);

        assertEquals(deltas.get(player(1)), -deltas.get(player(12)), 1e-9);
    }

    @Test
    void strengthIsAverageNotSum() {
        RatingLookup ratings = id -> 1.0;

        Map<Player, Double> deltas = rule.goal(players(1, 2), players(12, 13, 14, 15), ratings);

        assertEquals(0.5, deltas.get(player(1)), 1e-9);
    }

    @Test
    void reportsEachGoalsExpectedProbabilityToItsObserver() {
        List<Double> seen = new ArrayList<>();
        ResidualSource observed =
            new ResidualCreditRule(new LogisticLinkFunction(1.0), seen::add);

        observed.goal(players(1, 2, 3), players(12, 13, 14), ALL_ZERO);

        assertEquals(1, seen.size());
        assertEquals(0.5, seen.get(0), 1e-9);
    }

    @Test
    void observerlessConstructorStillWorks() {
        Map<Player, Double> deltas =
            rule.goal(players(1, 2, 3), players(12, 13, 14), ALL_ZERO);

        assertEquals(6, deltas.size());
    }

}
