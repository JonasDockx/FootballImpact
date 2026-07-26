package com.goalimpact.repair;

// One appearances row (minutes played), carried through untouched (item 26,
// stage 4b-2). The engine reads appearances to bound a match's exposure, so a
// released match must keep them or its players' minutes would vanish.
public record AppearanceRow(long gameId, int minutesPlayed) {
}
