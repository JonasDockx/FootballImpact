package com.goalimpact.repair;

import java.time.LocalDate;

// The matches-table columns the editor never touches (item 26, stage 4b-2): the
// fixture's identity and result, copied from the vendor straight through to the
// released row. status, provenance and commit_hash are the writer's to add - a
// repair is a Released match, and only the writer knows it - so they are not
// here. A repair changes lineups, never the scoreline.
public record MatchHeader(long gameId, LocalDate date, String competitionId,
    String season, String round, String competitionType,
    long homeClubId, String homeClubName,
    long awayClubId, String awayClubName,
    int homeClubGoals, int awayClubGoals) {

    // A name for each side that is always safe to print. The vendor leaves
    // home_club_name null on some fixtures - game 3936666 is one, and it is exactly
    // the absent-side shape the picker exists for - so the screen would otherwise
    // greet that repair with the word "null". The id is a poor label but a true
    // one, and it is what the operator would search the vendor by anyway.
    public String homeClubLabel() {
        return label(homeClubName, homeClubId);
    }

    public String awayClubLabel() {
        return label(awayClubName, awayClubId);
    }

    private static String label(String name, long clubId) {
        return name == null || name.isBlank() ? "club " + clubId : name;
    }
}
