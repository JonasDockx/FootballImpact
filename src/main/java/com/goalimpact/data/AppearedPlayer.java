package com.goalimpact.data;

// One player the vendor's appearances record names as having played a Held
// "no lineups" match (item 26, stage 4a) - the "appeared" rung of the worklist
// (CONTEXT 'Worklist tier'). No team sheet, but appearances names him exactly,
// with minutes. Match facts (date, competition, opponent) stay in the vendor
// games table and are joined at display time, exactly as for HeldAppearance.
public record AppearedPlayer(long gameId, long clubId, long playerId,
    String playerName, int minutes) {
}
