package com.goalimpact.engine;

import com.goalimpact.model.Team;

// What rating a player enters the run at, the first time it ever sees him
// (item 16).
//
// Until now the answer was 0.0 for everybody, which in this model does not
// mean "unknown" - it means EXACTLY WORLD-AVERAGE. For a Bundesliga debutant
// that is a defensible prior. For the eleven men of a fourth-tier side in the
// DFB-Pokal it is a donation: the strength gap at kickoff reads zero, expected
// goal difference over ninety minutes reads zero, and the Bundesliga side
// collects a full +4 of unearned residual for beating a team the model
// believed was its equal.
//
// The seed reads the club and nothing else, and CONTEXT's line holds - nothing
// club-level is stored or updated, no team carries a rating. The club decides
// only where a player STARTS; from his first minute he is an ordinary player
// whose rating moves on his own residuals.
@FunctionalInterface
public interface RatingSeed {

    double forDebutant(Team team);

    // The status quo, and what stage 1 pins: everyone enters at the population
    // mean, which is what the engine has always done.
    RatingSeed AVERAGE = team -> 0.0;

    // Item 16's seed: a club the run never prices enters below average by a
    // measured, pinned constant; everyone else still enters at 0. The penalty
    // is given as positive rating points and subtracted here, so the sign is
    // stated once and cannot be lost in a call site.
    static RatingSeed unpricedBelowAverage(ClubPools pools, double penalty) {
        if (penalty == 0.0) {
            return AVERAGE;     // the pinned-off flag, and byte-identical by construction
        }
        return team -> pools.unpriced(team.id()) ? -penalty : 0.0;
    }
}
