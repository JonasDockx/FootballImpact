package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.Map;
import java.util.Set;

public interface ResidualSource {
    // Given the on-pitch players of both teams for one goal, and the frozen
    // pre-match ratings, return each affected player's value change.
    Map<Player, Double> goal(Set<Player> scoringOnPitch, Set<Player> concedingOnPitch, RatingLookup ratings);

    // Called as each segment closes: a lineup-constant stretch of play, both
    // teams' on-pitch players (in no particular order - implementations must
    // treat the sides summetrically), its length, and the frozen ratings.
    // Goals-only rules ignore segments: the default returns no deltas.
    default Map<Player, Double> segment(Set<Player> teamA, Set<Player> teamB, double seconds, RatingLookup ratings) {
        return Map.of();
    }
}
