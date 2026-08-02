package com.goalimpact.engine;

// One match's age term, per player: how many rating points below his own peak
// a player is on the day this match is played (#21). Always >= 0, and 0 at the
// peak age itself.
//
// This is the seam the replay reads, and it is bound to ONE kickoff date -
// AgeingCurve.at(date) makes it - so the engine never carries a calendar. It
// is the same shape as RatingLookup for the same reason: the processor asks a
// question per player and is told a number, and where that number comes from
// is not its business.
//
// Read once per player per match rather than per rating read: the answer
// cannot change inside a match, and the rating seam is read once per goal and
// once per lineup-constant segment.
@FunctionalInterface
public interface AgePenalty {

    double forPlayer(long playerId);

    // The pinned-off arm, and the status quo the engine has always run: nobody
    // is penalised for his age, so lineup strength is the stored rating itself
    // (ADR 0016 stage 1). Byte-identical by construction - x - 0.0 is x for
    // every double.
    AgePenalty NONE = playerId -> 0.0;
}
