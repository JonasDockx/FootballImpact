package com.goalimpact.model;

public record Match(long matchId, Team home, Team away, int homeScore, int awayScore) {
}