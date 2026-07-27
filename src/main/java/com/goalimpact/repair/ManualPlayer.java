package com.goalimpact.repair;

import java.time.LocalDate;

// A player named by hand because no source names him (glossary 'Manual player',
// ADR 0012). He is one row of the sidecar's manual_players register: an id from
// the reserved range, a name, an optional date of birth and a note saying where
// he was found.
//
// The reserved range is a range test and not a flag column (ADR 0012, decision
// 1), so any code holding an id can ask isManual of it without a join.
// Transfermarkt's own ids are six and seven figures, leaving an order of
// magnitude of headroom below the first reserved id.
//
// Identity is the id and never the name (ADR 0012, decision 4): a name corrected
// later does not make him a different man, and does not rewrite the matches he
// already appears in.
public record ManualPlayer(long playerId, String playerName, LocalDate dateOfBirth,
    String note) {

    public static final long FIRST_ID = 1_000_000_000L;

    public static boolean isManual(long playerId) {
        return playerId >= FIRST_ID;
    }
}
