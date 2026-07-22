package com.goalimpact.data;

// A match that cannot form a coherent replay (ADR 0009): no lineups at
// all, an XI that isn't 11, no unique goalkeeper. Checked on purpose -
// 8,769 of the snapshot's 88,958 games are like this, so a caller that
// forgets to handle it has a bug, and exposure drives the update factor,
// so a silently dropped match manufactures false debutants.
public class UnusableMatchException extends Exception {

    private final String reason;

    // reason is the CATEGORY, counted across the run; detail identifies
    // this one match and goes only into the message. Keeping them apart is
    // what makes the skip report four lines instead of 576 - and ADR 0009
    // wants skips written, which a wall of one-off lines is not.
    public UnusableMatchException(String reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public UnusableMatchException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}

