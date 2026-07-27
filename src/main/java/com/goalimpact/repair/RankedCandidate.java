package com.goalimpact.repair;

// One row of the picker: a candidate, the rung the ranker put him on (glossary
// 'Candidate rank'), and - when he is already named in this match - the reason he
// cannot be added again.
//
// alreadyIn is null for an addable player. He is still listed when it is not,
// greyed rather than hidden: a name that vanishes reads as "not found", and the
// operator's next move would be to create a second copy of a man who is already
// there - the exact split ADR 0012 exists to prevent.
public record RankedCandidate(PlayerCandidate candidate, int rank, String alreadyIn) {

    public boolean unavailable() {
        return alreadyIn != null;
    }
}
