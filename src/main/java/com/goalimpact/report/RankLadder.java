package com.goalimpact.report;

// #47's reading aid for the chart's right edge: where a given Impact index
// stands in the active pool. Eleven pinned, dated constants - seven rank ticks
// and four band glosses - measured on the designated run, matches through
// 2026-07-06, over an active pool of 14,133 players (past 1,000 career minutes
// and having played within twelve months).
//
// NOT recomputed at build time, and that is the decision rather than an
// omission (#46): a refresh run must re-fit nothing, and it runs mid-season, so
// a live computation would rank every player against a half-played season's
// pool. Re-measure at +10% matches, like the other pinned constants.
//
// The index is still not a rank (ADR 0011) - a rank is drawn beside it, which
// is a different claim from the index being one.
public final class RankLadder {

    // The pool these eleven numbers describe, quoted on the page so a reader
    // can see what they are a reading of.
    public static final int POOL = 14_133;
    public static final String MEASURED = "2026-08-01";
    public static final String MATCHES_THROUGH = "2026-07-06";

    // Right-edge ticks, high to low. The ladder stops at Top 2000: below it the
    // right edge is blank, because a rank that deep says nothing worth drawing.
    public static final Tick[] TICKS = {
        new Tick(177.2, "Top 20"),
        new Tick(169.3, "Top 50"),
        new Tick(161.9, "Top 100"),
        new Tick(154.7, "Top 200"),
        new Tick(143.2, "Top 500"),
        new Tick(133.5, "Top 1000"),
        new Tick(123.5, "Top 2000"),
    };

    // The gloss beside each of ADR 0011's four bands. #40 left the bands
    // carrying numbers only and sent naming to #47; these are what #47 sent
    // back - a rank reading, not a borrowed quality label.
    public static final String[] BAND_GLOSSES = {"halfway", "≈Top 600", "≈Top 300", "≈Top 40"};

    public record Tick(double index, String label) {
    }

    private RankLadder() {
    }
}
