package com.goalimpact.data;

// One row of the maybe rung of a player's worklist (CONTEXT 'Worklist tier'):
// the Held match has neither team sheet nor appearances, so he is a candidate
// and not a fact - he turned out for one of its clubs within a month of the
// match date. MaybePlayer with the vendor's match facts joined on for the
// screen. nearbyMatches is the rung's ranking signal, counting how many of those
// nearby matches he actually played, so a regular outranks a long shot.
public record MaybeRow(MatchFacts match, long clubId, int nearbyMatches) {
}
