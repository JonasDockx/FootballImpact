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
}
