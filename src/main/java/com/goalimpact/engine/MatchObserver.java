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

    // Everything the history records, in one record (#24): what he brought in,
    // what he did, what the model expected of it, and what he left with. See
    // PlayerMatch for why it is a record and not a row of doubles.
    void playerMatch(PlayerMatch m);

    // The default for every caller that does not want a history - which is
    // every caller but the designated run's writer.
    MatchObserver NONE = m -> { };
}
