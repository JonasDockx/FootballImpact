package com.goalimpact.repair;

import java.time.LocalDate;

// One other match whose events name a player (item 17, slice 2, decision 9).
// The evidence offered when putting a name to an id the vendor never spells out:
// a date, a competition and two clubs are usually enough to find him in an
// outside source, which is the only place a name can honestly come from.
public record PlayerSighting(long gameId, LocalDate date, String competitionId,
    String season, String homeClubLabel, String awayClubLabel) {

    public String summary() {
        return date + "   " + competitionId + " " + season + "   "
            + homeClubLabel + " v " + awayClubLabel + "   (game " + gameId + ")";
    }
}
