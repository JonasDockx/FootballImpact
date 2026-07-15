package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.HashMap;
import java.util.Map;

public final class FlatCreditRule implements ResidualSource {
    @Override
    public Map<Player, Double> goal(Lineup scoring, Lineup conceding, RatingLookup ratings) {
        Map<Player, Double> deltas = new HashMap<>();
        for (Player p : scoring.players()) {
            deltas.put(p, 1.0);
        }
        for (Player p : conceding.players()) {
            deltas.put(p, -1.0);
        }
        return deltas;
    }
}
