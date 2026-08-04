package com.goalimpact.report;

import java.time.YearMonth;

// Where the career chart stops apologising for itself (#22, from #48).
//
// The glossary's Burn-in: at the start of a run every rating is still near its
// seed, so the population has not spread out yet. The mean is 100 by
// construction throughout - what is short is the SPREAD - and the chart's shaded
// zones are sigma multiples, so through the opening years every career is drawn
// against a ruler about a tenth too wide and reads mid-table when it was not.
// The chart says so by fading both lines from a floor up to full strength at the
// boundary below, marked once with a labelled vertical line.
//
// PINNED AND DATED, never derived by the replay and never computed at build time
// (#46's fence, #47's rule). A boundary the viewer worked out for itself would
// shift every time an unattended refresh run added a few matches, and yesterday's
// chart would disagree with today's with nobody having decided anything.
//
// The recipe, so the number below can be re-measured rather than trusted: walk
// the run month by month; over #47's active pool - past 1,000 career minutes and
// having played within the trailing twelve months - take the standard deviation
// of the Impact index; the boundary is the first month it comes within 5% of its
// settled value, the spread the run ends on. The spread and not the Top-100
// threshold, because the zones ARE sigma multiples: the compression and the ruler
// are made of the same quantity. #47's pool rather than a second one, so the
// boundary and the rank ladder are measured over the same people and cannot
// drift apart. Backward-looking by construction - asking when the spread reached
// its settled value needs the settled value - which is exactly why the answer is
// pinned rather than live.
//
// Re-measured at ADR 0013's pass three, alongside #40's bands, #47's ladder and
// #41's curve: it is the same query as #40's, over the same pool.
public final class BurnIn {

    // Measured on the designated run
    // TRANSFERMARKT-ALL-k0.10-K01.00-H4000-f0.05-h2.00-s2.58: 85,050 matches,
    // 2012-07-09 through 2026-07-06 (#43 - a calibration constant states the
    // population it belongs to).
    public static final String MEASURED = "2026-08-04";

    // The run's opening month, where the ramp starts. Pinned beside the boundary
    // rather than read off the data, so the two ends of one ramp move together
    // and only when somebody decides they should.
    public static final String FROM = "2012-07";

    // The month the spread arrives: 19.674 against a settled 20.539, the first
    // reading within 5%. 2017-03 is 19.481 and misses. The active pool there is
    // 7,078 players.
    public static final String BOUNDARY = "2017-04";
    public static final double SETTLED_SD = 20.539;
    public static final double SD_AT_BOUNDARY = 19.674;
    public static final double SD_BEFORE_BOUNDARY = 19.481;
    public static final double TOLERANCE = 0.05;
    public static final int POOL_AT_BOUNDARY = 7_078;

    // How pale the lines start. The floor is load-bearing rather than cosmetic
    // (#48): a fade that approaches invisible quietly becomes "do not draw it",
    // which was rejected outright - a 2013 rating is a real measurement that is
    // merely squashed, and the ordering within the opening years is informative
    // even where the level is not. At 35% the shape, the peak and a transfer dip
    // all stay traceable; what stops being legible is exactly the claim the fade
    // is disowning, where the career sat against a zone.
    public static final double OPACITY_FLOOR = 0.35;

    // The chart's own axis: absolute months, year*12 + month-1, the same one
    // ViewerWriter samples careers onto. Converted here rather than written out
    // as a magic integer, so the boundary is edited in the form it was measured
    // in.
    public static final int FROM_MONTH = month(FROM);
    public static final int BOUNDARY_MONTH = month(BOUNDARY);

    private static int month(String yearMonth) {
        YearMonth ym = YearMonth.parse(yearMonth);
        return ym.getYear() * 12 + ym.getMonthValue() - 1;
    }

    private BurnIn() {
    }
}
