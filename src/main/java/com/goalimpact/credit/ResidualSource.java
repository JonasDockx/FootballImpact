package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.Map;

public interface ResidualSource {
    // Given both sides' on-pitch lineups for one goal, and the frozen
    // pre-match ratings, return each affected player's value change.
    Map<Player, Double> goal(Lineup scoring, Lineup conceding, RatingLookup ratings);

    // Called as each segment closes: a lineup-constant stretch of play, both
    // sides (in no particular order - implementations must treat them
    // symmetrically), its length, and the frozen ratings.
    // Goals-only rules ignore segments: the default returns no deltas.
    default Map<Player, Double> segment(Lineup teamA, Lineup teamB, double seconds, RatingLookup ratings) {
        return Map.of();
    }
}
