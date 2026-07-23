package com.goalimpact.data;

// One player who MIGHT have been in a Held "no lineups" match that carries no
// appearances either (item 26, stage 4a) - the "maybe" rung of the worklist
// (CONTEXT 'Worklist tier'). A candidate, not a fact: he turned out for one of
// the match's clubs within a month of its date. nearbyMatches counts how many
// such nearby matches he played, so the repair GUI can rank a regular above a
// long shot.
public record MaybePlayer(long gameId, long clubId, long playerId,
    String playerName, int nearbyMatches) {
}
