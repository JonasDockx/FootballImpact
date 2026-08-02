package com.goalimpact.report;

import java.util.Locale;

// ADR 0011's scale, in one place because two things read it and they must not
// drift: the viewer's SQL, which rescales 2M stored Values, and any Java that
// wants to quote a single one. The prototype (#35) duplicated the rescale in
// its idx.sql, where nothing checked it - #46 named that as the reason the
// viewer is built in Java rather than SQL plus shell glue.
//
// Pinned and dated like the base scoring rate and h: mean and standard
// deviation of Value over the designated run's 25,334 players past 1,000
// minutes, measured 2026-07-22. Re-measured only when a large new era or
// competition lands, never per run.
public final class ImpactIndex {

    // Where the population average sits on the index.
    public static final double CENTRE = 100.0;

    // One standard deviation of Value, in index points.
    public static final double POINTS_PER_SD = 20.0;

    public static final double VALUE_MEAN = 1.8374;
    public static final double VALUE_SD = 7.1729;

    // ADR 0011: a line starts at 1,000 career minutes, which is also the
    // population the two constants above were measured over. The viewer draws
    // exactly this population (#22, option 1).
    public static final int ELIGIBLE_MINUTES = 1000;

    // ADR 0011's four quality bands. Flat by decision (#40): they carry no age
    // term, so the 11.4% of drawable careers with no date of birth are judged
    // against the same four lines as everyone else.
    public static final double[] BANDS = {100, 140, 150, 170};

    private ImpactIndex() {
    }

    public static double of(double value) {
        return CENTRE + POINTS_PER_SD * (value - VALUE_MEAN) / VALUE_SD;
    }

    // The same arithmetic as an SQL expression over a column, so the query the
    // viewer runs cannot disagree with of() above. Formatted from the constants
    // rather than written out, which is the whole point of it living here.
    public static String sql(String valueColumn) {
        return String.format(Locale.ROOT, "%s + %s*(%s - %s)/%s",
            trim(CENTRE), trim(POINTS_PER_SD), valueColumn, trim(VALUE_MEAN), trim(VALUE_SD));
    }

    // %.4f always leaves a decimal point, so the trailing zeros always come off:
    // 100.0000 reads 100 in a query, which is what a reader compares to the ADR.
    private static String trim(double d) {
        return String.format(Locale.ROOT, "%.4f", d)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
    }
}
