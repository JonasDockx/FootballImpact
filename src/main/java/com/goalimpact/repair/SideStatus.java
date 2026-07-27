package com.goalimpact.repair;

// How one side of a match stands, counted rather than eyeballed (item 17, user,
// 2026-07-27). The editor's problems() says *what* is wrong in exactly the
// loader's own words - an agreement pinned by LoaderAgreementTest and not to be
// bent for readability - so "XI is not 11" cannot say which side, or by how many.
// This is the other half: named, counted, and shown whether or not anything is
// wrong, so filling a side is never a matter of counting rows on screen.
//
// It carries no verdict. problems() alone decides whether a match may be
// Released; this only reports what a side looks like right now.
public record SideStatus(long clubId, String clubName, int starters, int bench,
    int startingGoalkeepers) {

    private static final int XI = 11;

    public boolean complete() {
        return starters == XI;
    }

    // Positive when the side is short, negative when it has too many, zero when
    // it is right - so one number answers "how far off am I?" in both directions.
    public int shortOfEleven() {
        return XI - starters;
    }

    public String summary() {
        return clubName + ": " + starters + " of " + XI + " starters" + shortfall()
            + ", " + goalkeepers() + ", " + bench + " on the bench";
    }

    private String shortfall() {
        if (complete()) {
            return "";
        }
        return shortOfEleven() > 0
            ? " (" + shortOfEleven() + " short)"
            : " (" + -shortOfEleven() + " too many)";
    }

    private String goalkeepers() {
        if (startingGoalkeepers == 0) {
            return "no starting goalkeeper";
        }
        return startingGoalkeepers == 1
            ? "1 goalkeeper"
            : startingGoalkeepers + " starting goalkeepers";
    }
}
