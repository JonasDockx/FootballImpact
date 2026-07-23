package com.goalimpact.data;

// One hit from the player search: a player who has worklist rows, and how many
// (item 26, stage 4b-1). The search runs over the worklist tables' own
// player_name rather than the vendor players table, so it can only ever offer a
// player there is something to do for - at the known cost that an empty result
// cannot tell a complete career from a misspelled name, which the screen says
// out loud. A vendor-backed picker carrying DOB and last-seen club is item 17's
// ranked picker, and belongs to stage 4b-2.
public record WorklistPlayer(long playerId, String playerName,
    int missingMatches) {
}
