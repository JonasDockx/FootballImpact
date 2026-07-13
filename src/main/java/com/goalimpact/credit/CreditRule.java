package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.Map;
import java.util.Set;

public interface CreditRule {
    // Given the on-pitch players of both teams for one goal, and the frozen
    // pre-match ratings, return each affected player's value change.
    Map<Player, Double> credit(Set<Player> scoringOnPitch, Set<Player> concedingOnPitch, RatingLookup ratings);
}
