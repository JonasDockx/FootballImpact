package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FlatCreditRule implements CreditRule {
    @Override
    public Map<Player, Double> credit(Set<Player> scoringOnPitch, Set<Player> concedingOnPitch, RatingLookup ratings) {
        Map<Player, Double> deltas = new HashMap<>();
        for (Player p : scoringOnPitch) {
            deltas.put(p, 1.0);
        }
        for (Player p : concedingOnPitch) {
            deltas.put(p, -1.0);
        }
        return deltas;
    }
}
