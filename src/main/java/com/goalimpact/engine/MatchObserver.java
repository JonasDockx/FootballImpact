package com.goalimpact.engine;

import java.time.LocalDate;

// ADR 0011: the career-history seam. MatchProcessor tells this, once per
// player per match at the final whistle, everything a rating history needs -
// what the player brought in, what he did, and what he left with. It hears;
// it never changes a rating. Same shape as the ScoringWindow that already
// listens to every goal's expected probability.
@FunctionalInterface
public interface MatchObserver {

    // Stamped by the caller before each match. The engine replays a bare
    // List<MatchEvent> and never learns which Match it came from (ADR 0004),
    // so the identity has to arrive from outside. Default no-op: an observer
    // that only counts does not care which match it is.
    default void startMatch(long matchId, LocalDate date) { }

    // minutesBefore and ratingBefore are the FROZEN pre-match values (the
    // rating period of ADR 0005), so ratingBefore plus this match's update is
    // exactly ratingAfter.
    void playerMatch(long playerId, double minutesBefore, double ratingBefore,
        double residual, double minutesPlayed, double ratingAfter);

    // The default for every caller that does not want a history - which is
    // every caller until stage 2.
    MatchObserver NONE = (id, minutesBefore, ratingBefore, residual, played, after) -> { };
}
