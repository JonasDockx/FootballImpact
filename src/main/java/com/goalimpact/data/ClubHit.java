package com.goalimpact.data;

// One club offered by the club search (item 29, slice 2), with how much work it
// has. Unlike the player search, which can only offer a player there is work
// for, this one offers every club the vendor has fixtures for - so a count of
// zero is a real answer meaning "this club is complete", and a miss means the
// name is wrong rather than either.
public record ClubHit(long clubId, String clubName, int heldMatches) {
}
