package com.goalimpact.repair;

import java.time.LocalDate;

// One name the picker may offer, carrying the evidence a rank is computed from
// (item 17, slice 1, decision 10). The reader in data fills this in from SQL; the
// ranker in this package turns it into an order and never asks a database
// anything - so the rule that will be tuned lives outside the query string.
//
// nearbyMatches counts the matches this player turned out for at this club within
// a month of this one, over vendor appearances and sidecar lineups together and
// DISTINCT by game (decision 5), so a man created on one matchday is already a
// regular on the next. everPlayedForClub is the same union with the window
// dropped. dateOfBirth and position exist only to split identical names on
// screen; both may be absent.
public record PlayerCandidate(long playerId, String playerName, String position,
    LocalDate dateOfBirth, int nearbyMatches, boolean everPlayedForClub) {

    public boolean manual() {
        return ManualPlayer.isManual(playerId);
    }

    // The stronger of two readings of the same man - the picker's three arms can
    // each name him, and only the union of what they know should reach the screen.
    PlayerCandidate merge(PlayerCandidate other) {
        return new PlayerCandidate(playerId,
            firstKnown(playerName, other.playerName, ""),
            firstKnown(nullIfUnknown(position), nullIfUnknown(other.position),
                LineupEntry.UNKNOWN_POSITION),
            dateOfBirth != null ? dateOfBirth : other.dateOfBirth,
            Math.max(nearbyMatches, other.nearbyMatches),
            everPlayedForClub || other.everPlayedForClub);
    }

    private static String nullIfUnknown(String position) {
        return LineupEntry.UNKNOWN_POSITION.equals(position) ? null : position;
    }

    private static String firstKnown(String mine, String theirs, String neither) {
        if (mine != null && !mine.isBlank()) {
            return mine;
        }
        return theirs != null && !theirs.isBlank() ? theirs : neither;
    }
}
