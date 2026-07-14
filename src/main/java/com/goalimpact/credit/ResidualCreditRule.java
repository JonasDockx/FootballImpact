package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// Rule C: credit = actual - expected. A lineup's strength is the average of
// its on-pitch players' frozen ratings; the link function turns the strength
// gap into the expected probability P that the scoring side scores this goal.
// Scorers each get +(1 - P), conceders -(1 - P): an expected goal moves
// ratings barely, an upset moves them a lot, and each goal is zero-sum.

public final class ResidualCreditRule implements CreditRule {

    private final LinkFunction link;
    private final DoubleConsumer pObserver;

    public ResidualCreditRule(LinkFunction link) {
        this(link, p -> {});
    }

    // pObserver is told the expected probability P of every goal this rule
    // credits - the hook prediction-quality measurement hangs on.
    public ResidualCreditRule(LinkFunction link, DoubleConsumer pObserver) {
        this.link = link;
        this.pObserver = pObserver;
    }

    @Override
    public Map<Player, Double> credit(Set<Player> scoringOnPitch, Set<Player> concedingOnPitch, RatingLookup ratings) {
        double gap = strength(scoringOnPitch, ratings) - strength(concedingOnPitch, ratings);
        double expectedP = link.expected(gap);
        pObserver.accept(expectedP);
        double residual = 1.0 - expectedP;

        Map<Player, Double> deltas = new HashMap<>();
        for (Player p : scoringOnPitch) {
            deltas.put(p, residual);
        }
        for (Player p : concedingOnPitch) {
            deltas.put(p, -residual);
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
