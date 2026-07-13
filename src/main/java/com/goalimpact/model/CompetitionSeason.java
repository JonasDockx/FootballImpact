package com.goalimpact.model;

// One entry of competitions.json: a competition in one season - the unit
// StatsBomb organises match files by (matches/<competitionId>/<seasonId>.json).
public record CompetitionSeason(int competitionId, int seasonId,
    String competitionName, String seasonName, String gender) {
}
