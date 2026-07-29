package com.goalimpact.data;

// One Held match, recorded by the gate that rejected it (item 29, slice 1).
//
// The worklist's other three tables are player rows, so a match reaches them
// only when some tier names somebody for it - and 46% of Held matches name
// nobody, which is why they could not be opened at all. This is the match-level
// spine that fixes that: a row exists for every Held match whatever the reason,
// and it carries both club ids, which the vendor always has. Match facts (date,
// competition, names) deliberately stay in the vendor games table and are joined
// at display time, exactly as for HeldAppearance.
public record HeldMatch(long gameId, long homeClubId, long awayClubId, String reason,
    RepairSource repairSource) {
}
