package com.goalimpact.engine;

// One player's story from one match, as MatchObserver hears it at the final
// whistle (ADR 0011's seam).
//
// A record rather than eight positional arguments, and #24 is the reason: two
// of them are doubles in minutes and three are doubles in goals, so a
// transposition among them is invisible to the compiler and produces a history
// that is wrong without ever failing. The names are the check.
//
// ADR 0004 is untouched. This is a message from the engine to a listener, never
// an attribute on Player: record equality still drives on-pitch set removal, and
// nothing here is ever put in a Set of players.
public record PlayerMatch(
    long playerId,

    // The FROZEN pre-match values (the rating period of ADR 0005), so
    // ratingBefore plus this match's update is exactly ratingAfter. Since
    // ADR 0016 the two ratings are P, the estimated peak - the number the
    // update moves - not what he was worth on the day.
    double minutesBefore,
    double ratingBefore,

    // #24: the residual's two halves, told apart so a match log can say WHY a
    // rating moved rather than only that it did.
    //
    // goalValue is the scoreboard's full +-1 jumps while he was on the pitch;
    // expectationDrained is the expected goal difference that drained out of his
    // segments, carrying the sign it contributes with (negative for the side the
    // strength gap favours). So goalValue + expectationDrained is residual - to
    // within floating-point associativity, and not bit-for-bit: residual is
    // still summed in event order, interleaved, because that summation IS the
    // number that moved the rating and it must not change to be observed.
    double goalValue,
    double expectationDrained,
    double residual,

    double minutesPlayed,
    double ratingAfter) {
}
