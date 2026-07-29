package com.goalimpact.data;

// What a Held match still has to offer when it is opened for repair (CONTEXT
// 'Repair source'), on a ladder of falling completeness. A property of the
// match, and so the match-scoped counterpart of a Worklist tier: a tier says how
// sure we are that a player belongs to a match, a source says what the match
// itself still has.
public enum RepairSource {

    // A team sheet exists and is merely broken - the gate rejected it for what
    // it says, not for its absence - so nearly a whole lineup is already there.
    TEAM_SHEET,

    // No team sheet, but the appearances record names everyone who played.
    APPEARANCES,

    // Neither, so the lineup is a Derived lineup read off whoever scored, was
    // booked, came on or went off - most of a lineup, never all of one.
    EVENTS,

    // No record survives at all. Such a match can be listed but not repaired:
    // its events cannot be reconstructed, so releasing it would assert that
    // nothing happened in it.
    NOTHING
}
