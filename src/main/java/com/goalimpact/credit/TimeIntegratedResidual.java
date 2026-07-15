package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// ADR 0007: the time-integrated residual. Goals are full +-1 scoreboard
// jumps; expectation drains continuously from every segment. A side's
// scoring rate is the base rate bent multiplicatively by the strength gap:
// rate = base * e^(+-gain/2 * gap)     (goals per minute)
// which makes "who scores the next goal?" exactly the logistic curve in
// gain * gap that the goals-only rule used - the harness measures the same
// curve, only the ratings feeding it evolve differently.

public final class TimeIntegratedResidual implements ResidualSource {
    
    private final double baseRate;  // goals per team-minute between equal lineups
    private final double gain;      // who-scores logistic gain (k)
    private final DoubleConsumer pObserver;

    public TimeIntegratedResidual(double baseRate, double gain, DoubleConsumer pObserver) {
        if (baseRate <= 0) {
            throw new IllegalArgumentException("base rate must be positive, was "+ baseRate);
        }
        this.baseRate = baseRate;
        this.gain = gain;
        this.pObserver = pObserver;
    }

    @Override
    public Map<Player, Double> goal(Set<Player> scoringOnPitch, Set<Player> concedingOnPitch, RatingLookup ratings) {
        double gap = strength(scoringOnPitch, ratings) - strength(concedingOnPitch, ratings);
        pObserver.accept(1.0 / (1.0 + Math.exp(-gain * gap)));

        Map<Player, Double> deltas = new HashMap<>();
        for (Player p : scoringOnPitch) {
            deltas.put(p, 1.0);
        }
        for (Player p : concedingOnPitch) {
            deltas.put(p, -1.0);
        }
        return deltas;
    }

    @Override
    public Map<Player, Double> segment(Set<Player> teamA, Set<Player> teamB, double seconds, RatingLookup ratings) {
        double gap = strength(teamA, ratings) - strength(teamB, ratings);
        // Team A's expected goal-difference rate per minute: the gap bends
        // the base rate up for one side, down by the same factor for the
        // other; the difference between the two rates is what drains.
        double gdPerMinute = baseRate * (Math.exp(gain / 2 * gap) - Math.exp(-gain / 2 * gap));
        double drain = gdPerMinute * seconds / 60.0;

        Map<Player, Double> deltas = new HashMap<>();
        for (Player p : teamA) {
            deltas.put(p, -drain);
        }
        for (Player p : teamB) {
            deltas.put(p, drain);
        }
        return deltas;
    }

    private double strength(Set<Player> onPitch, RatingLookup ratings) {
        if (onPitch.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Player p : onPitch) {
            sum += ratings.rating(p.id());
        }
        return sum / onPitch.size();
    }
}
