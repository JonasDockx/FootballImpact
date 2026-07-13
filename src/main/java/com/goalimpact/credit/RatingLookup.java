package com.goalimpact.credit;

// How a credit rule sees player strength: the FROZEN pre-match rating of a
// player. Players unknown to the lookup rate 0.0 (the debutant baseline).

@FunctionalInterface
public interface RatingLookup {
    double rating(long playerId);
}
