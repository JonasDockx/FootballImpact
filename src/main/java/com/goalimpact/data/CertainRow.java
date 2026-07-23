package com.goalimpact.data;

// One row of the certain rung of a player's worklist (CONTEXT 'Worklist tier'):
// he is named in this Held match's broken team sheet, so his presence is a fact
// the gate read before rejecting the match. HeldAppearance is what the loader
// recorded as it threw; this is that row with the vendor's match facts joined on
// for the screen. started is the rung's ranking signal - a Held match is
// unreplayable, so "named in the lineup" is the only certain fact, and the flag
// separates a starter from an unused sub without guessing. reason is the gate's
// verdict verbatim.
public record CertainRow(MatchFacts match, long clubId, boolean started,
    String reason) {
}
