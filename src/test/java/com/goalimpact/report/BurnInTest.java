package com.goalimpact.report;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #48's boundary, held to its own recipe. Nothing here re-measures anything -
// the numbers are pinned and were measured once - but the arithmetic BETWEEN
// them is checkable, and it is the arithmetic that would rot silently: a
// boundary edited without its measurement, or a month index that disagrees with
// the axis the chart draws it on.
class BurnInTest {

    // The axis the page draws on is absolute months (year*12 + month-1), the
    // same one ViewerWriter samples careers onto. A boundary on any other axis
    // would land the vertical line somewhere plausible and wrong.
    @Test
    void theBoundarySitsOnTheChartsOwnMonthAxis() {
        assertEquals(2017 * 12 + 3, BurnIn.BOUNDARY_MONTH);
        assertEquals(2012 * 12 + 6, BurnIn.FROM_MONTH);
        assertEquals(YearMonth.of(2017, 4), YearMonth.parse(BurnIn.BOUNDARY));
    }

    // The recipe (#48): the first month whose spread is within 5% of the
    // settled value. Both readings are pinned beside the boundary, so the claim
    // the constant makes can be checked without a database.
    @Test
    void theBoundaryIsWhereTheSpreadReachesItsTolerance() {
        double needed = BurnIn.SETTLED_SD * (1 - BurnIn.TOLERANCE);
        assertTrue(BurnIn.SD_AT_BOUNDARY >= needed,
            BurnIn.SD_AT_BOUNDARY + " is not within " + BurnIn.TOLERANCE + " of " + BurnIn.SETTLED_SD);
        // And the month before it is not: a boundary that would have been
        // reached earlier is the wrong month, not a conservative one.
        assertTrue(BurnIn.SD_BEFORE_BOUNDARY < needed,
            BurnIn.SD_BEFORE_BOUNDARY + " already clears " + needed);
    }

    // The floor is load-bearing (#48): a fade approaching invisible is
    // truncation in disguise, and truncation was rejected.
    @Test
    void theFadeNeverApproachesInvisible() {
        assertTrue(BurnIn.OPACITY_FLOOR >= 0.3 && BurnIn.OPACITY_FLOOR < 1,
            "legibility floor out of range: " + BurnIn.OPACITY_FLOOR);
    }

    // A ramp that ran backwards, or over no time at all, would fade nothing.
    @Test
    void theRampRunsForwardsFromTheStartOfTheRun() {
        assertTrue(BurnIn.FROM_MONTH < BurnIn.BOUNDARY_MONTH);
    }
}
