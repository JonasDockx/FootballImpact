package com.goalimpact.model;

import java.time.LocalDate;

public record Match(long matchId, LocalDate date, Team home, Team away, 
    int homeScore, int awayScore, HomeSide homeSide) {
    // The loader's verdict on who, if anyone, genuinely plays at its own
    // venue this match. HOME/AWAY refer to the labeled teams; NEITHER is a
    // neutral venue. Never read the label itself as if it meant this.
    public enum HomeSide { HOME, AWAY, NEITHER }
}