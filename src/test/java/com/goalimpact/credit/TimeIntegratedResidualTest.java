package com.goalimpact.credit;

import com.goalimpact.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeIntegratedResidualTest {

    // One rating point of gap doubles one side's rate and halves the other's:
    // the grill's Brazil-Andorra table falls out exactly.
    private static final double GAIN = 2 * Math.log(2);

    private static final Player A1 = new Player(1, "A1");
    private static final Player A2 = new Player(2, "A2");
    private static final Player B1 = new Player(12, "B1");
    private static final Player B2 = new Player(13, "B2");

    // Team A players rate 1.0, everyone else 0.0: gap exactly 1.
    private static final RatingLookup RATINGS = id -> id < 10 ? 1.0 : 0.0;

    private static Lineup neutral(Player... players) {
        return new Lineup(Set.of(players), false, Set.of());
    }

    private static Lineup home(Player... players) {
        return new Lineup(Set.of(players), true, Set.of());
    }

    
    @Test
    void goalIsAFullJumpAndReportsTheWhoScoresProbability() {
        List<Double> observed = new ArrayList<>();
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 0.0, observed::add);

        Map<Player, Double> deltas = rule.goal(neutral(A1, A2), neutral(B1, B2), RATINGS);

        assertEquals(1.0, deltas.get(A1), 1e-9);  // full jump - no surprise scaling
        assertEquals(-1.0, deltas.get(B1), 1e-9);
        assertEquals(0.8, observed.get(0), 1e-9); // rates 2 : 0.5 -> 80% who-scores
    }

    @Test
    void drainIsTheExpectedGoalDifferenceOverTheSegment() {
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 0.0, p -> {});

        // 60 minutes at rates 0.04 vs 0.01 goals/min: expected GD 1.8.
        Map<Player, Double> deltas = rule.segment(neutral(A1, A2), neutral(B1, B2), 3600, RATINGS);

        assertEquals(-1.8, deltas.get(A1), 1e-9); // favourites owe the drain
        assertEquals(1.8, deltas.get(B1), 1e-9);  // underdogs earn it by surviving
    }

    @Test
    void sidesArePassedInArbitraryOrderAndMirrorExactly() {
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 0.0, p -> {});

        Map<Player, Double> deltas = rule.segment(neutral(B1, B2), neutral(A1, A2), 3600, RATINGS);

        assertEquals(1.8, deltas.get(B1), 1e-9);  // same answer with sides swapped
        assertEquals(-1.8, deltas.get(A1), 1e-9);
    }

    @Test
    void equalStrengthSidesDrainNothing() {
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 0.0, p -> {});
        Player c1 = new Player(22, "C1");

        // 2 players vs 1: Strength is an average, so counts don't matter.
        Map<Player, Double> deltas = rule.segment(neutral(B1, B2), neutral(c1), 3600, RATINGS);

        assertEquals(0.0, deltas.get(B1), 1e-9);
        assertEquals(0.0, deltas.get(c1), 1e-9);
    }

    @Test
    void homeAdvantageTiltsTheWhoScoresProbability() {
        List<Double> observed = new ArrayList<>();
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 1.0, observed::add);
        Player c1 = new Player(22, "C1");

        rule.goal(home(B1, B2), neutral(c1), RATINGS); // equal ratings, scorer at home

        assertEquals(0.8, observed.get(0), 1e-9); // h = 1 point = the gap-1 table
    }

    @Test
    void homeAdvantageDrainsExpectationFromTheHomeSide() {
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 1.0, p -> {});
        Player c1 = new Player(22, "C1");

        Map<Player, Double> deltas = rule.segment(home(B1, B2), neutral(c1), 3600, RATINGS);

        assertEquals(-1.8, deltas.get(B1), 1e-9); // the home side owes the drain
        assertEquals(1.8, deltas.get(c1), 1e-9);  // visitors earn it by surviving
    }

    @Test
    void homeAdvantageIsSideOrderIndependent() {
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 1.0, p -> {});
        Player c1 = new Player(22, "C1");

        Map<Player, Double> deltas = rule.segment(neutral(c1), home(B1, B2), 3600, RATINGS);

        assertEquals(-1.8, deltas.get(B1), 1e-9); // same answer, sides swapped
        assertEquals(1.8, deltas.get(c1), 1e-9);
    }

    @Test
    void homeAdvantageCanExactlyCancelAQualityGap() {
        TimeIntegratedResidual rule = new TimeIntegratedResidual(0.02, GAIN, 1.0, p -> {});

        // The A-side is a full point stronger; the B-side is at home with
        // h = 1: venue exactly cancels quality, so nothing drains.
        Map<Player, Double> deltas = rule.segment(home(B1, B2), neutral(A1, A2), 3600, RATINGS);

        assertEquals(0.0, deltas.get(B1), 1e-9);
        assertEquals(0.0, deltas.get(A1), 1e-9);
    }

}
