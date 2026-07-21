package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.Map;

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
    private final double homeAdvantage; // rating points added to the home side's effective strength (ADR 0008)
    private final boolean fieldPlayersOnly; // item 11: exclude goalkeepers from the strength average
    private final DoubleConsumer pObserver;

    public TimeIntegratedResidual(double baseRate, double gain, double homeAdvantage, boolean fieldPlayersOnly, DoubleConsumer pObserver) {
        if (baseRate <= 0) {
            throw new IllegalArgumentException("base rate must be positive, was "+ baseRate);
        }
        this.baseRate = baseRate;
        this.gain = gain;
        this.homeAdvantage = homeAdvantage;
        this.fieldPlayersOnly = fieldPlayersOnly;
        this.pObserver = pObserver;
    }

    @Override
    public Map<Player, Double> goal(Lineup scoring, Lineup conceding, RatingLookup ratings) {
        double gap = strength(scoring, ratings) - strength(conceding, ratings)
        + (scoring.home() ? homeAdvantage : 0) - (conceding.home() ? homeAdvantage : 0);
        pObserver.accept(1.0 / (1.0 + Math.exp(-gain * gap)));

        Map<Player, Double> deltas = new HashMap<>();
        for (Player p : scoring.players()) {
            deltas.put(p, 1.0);
        }
        for (Player p : conceding.players()) {
            deltas.put(p, -1.0);
        }
        return deltas;
    }

    @Override
    public Map<Player, Double> segment(Lineup teamA, Lineup teamB, double seconds, RatingLookup ratings) {
        double gap = strength(teamA, ratings) - strength(teamB, ratings)
        + (teamA.home() ? homeAdvantage : 0) - (teamB.home() ? homeAdvantage : 0);
        // Team A's expected goal-difference rate per minute: the gap bends
        // the base rate up for one side, down by the same factor for the
        // other; the difference between the two rates is what drains.
        double gdPerMinute = baseRate * (Math.exp(gain / 2 * gap) - Math.exp(-gain / 2 * gap));
        double drain = gdPerMinute * seconds / 60.0;

        Map<Player, Double> deltas = new HashMap<>();
        for (Player p : teamA.players()) {
            deltas.put(p, -drain);
        }
        for (Player p : teamB.players()) {
            deltas.put(p, drain);
        }
        return deltas;
    }

    // Average rating of the players the rule counts: everyone, or field
    // players only when the flag excludes Goalkeepers (item 11). The
    // counted == 0 guard covers empty lineups and the degenerate
    // all-goalkeepers case alike.
    private double strength(Lineup side, RatingLookup ratings) {
        double sum = 0.0;
        int counted = 0;
        for (Player p : side.players()) {
            if (fieldPlayersOnly && side.goalkeepers().contains(p)) {
                continue;
            }
            sum += ratings.rating(p.id());
            counted ++;
        }
        if (counted == 0) {
            return 0.0;
        }
        return sum / counted;
    }
    
}
