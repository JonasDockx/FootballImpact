package com.goalimpact.data;

// One row of a club's Held-match list (item 29, slice 2).
//
// It carries no Worklist tier, and that is the point: a tier says how sure we
// are that a *player* belongs to a match, and a club view is never unsure the
// club belongs - the fixture names both sides, which is why this door reaches
// every Held match. What it carries instead is the Repair source, the
// match-scoped ladder: what the match still has to be repaired from. The gate's
// own reason rides along beside it, because the source says what you will be
// working with and the reason says why the match broke, and they are not the
// same question.
public record ClubMatchRow(MatchFacts match, String reason, RepairSource repairSource) {
}
