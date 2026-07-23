package com.goalimpact.data;

import java.time.LocalDate;

// The vendor's facts about one match, joined in at display time for a worklist
// row (item 26, stage 4b-1). Every worklist table deliberately stores lean
// pointers only - player, club, game id, and the tier's own signal - because
// match facts live in the vendor games table, so a snapshot refresh can never
// leave the worklist quoting a stale date (item 25). This record is where that
// join lands. Both club names come straight off games, so no club table is
// involved.
public record MatchFacts(long gameId, LocalDate date, String competition,
    String round, String homeName, String awayName) {
}
