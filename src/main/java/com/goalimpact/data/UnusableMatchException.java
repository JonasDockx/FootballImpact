package com.goalimpact.data;

// A match that cannot form a coherent replay (ADR 0009): no lineups at
// all, an XI that isn't 11, no unique goalkeeper. Checked on purpose -
// 8,769 of the snapshot's 88,958 games are like this, so a caller that
// forgets to handle it has a bug, and exposure drives the update factor,
// so a silently dropped match manufactures false debutants.
public class UnusableMatchException extends Exception {

    public UnusableMatchException(String reason) {
        super(reason);
    }
}
