package com.goalimpact.data;

// One row of the appeared rung of a player's worklist (CONTEXT 'Worklist tier'):
// the Held match carries no team sheet, but the vendor's appearances record
// names him exactly, with minutes. AppearedPlayer with the vendor's match facts
// joined on for the screen. minutes is the rung's ranking signal, and a genuine
// measure of how much the missing match dented the career.
public record AppearedRow(MatchFacts match, long clubId, int minutes) {
}
