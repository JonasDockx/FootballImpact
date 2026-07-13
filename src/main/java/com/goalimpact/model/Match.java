package com.goalimpact.model;

import java.time.LocalDate;

public record Match(long matchId, LocalDate date, Team home, Team away, int homeScore, int awayScore) {
}