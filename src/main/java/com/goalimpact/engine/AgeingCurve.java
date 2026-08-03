package com.goalimpact.engine;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Map;

// The population ageing curve (#21, ADR 0016): how far below his own peak a
// player of a given age is, in rating points.
//
// The arrow points this way round, and it is the whole point of ADR 0016. The
// engine stores P, a player's estimated PEAK, and what he contributes to
// lineup strength today is P - D(age today). So this curve is subtracted
// INSIDE the replay, before a single goal is judged - the ageing expectation
// lives in the match, not on the chart.
//
// Piecewise linear in EXACT age between a handful of fixed knots. No year
// bins, which is what dissolves #21's original "a year is birthday to
// birthday" problem: a player is 22.37 years old on the day, not "in his 22nd
// year", so no competition calendar and no birthday boundary enters anywhere.
//
// The table is FITTED ONCE OUTSIDE THE ENGINE, then pinned and dated like the
// base scoring rate, h, K0 and H. The engine reads a lookup and stays a single
// sequential pass; nothing here is tuned by the grid.
public final class AgeingCurve {

    // The knot ages the curve is defined at, pinned 2026-08-02. These are the
    // fit's design and they are deliberate: dense where football careers
    // actually turn (the climb to the low twenties, the plateau, the fall after
    // thirty) and sparse at the ends, where the population thins out. #38's
    // research says the original describes a plateau from 25 to 30 and a steep
    // fall after 30; these knots can express that without assuming it.
    //
    // They are a shape, not a measurement, so unlike the penalties below they
    // carry no population - the stage 2 fit may move them, and moving them is a
    // design decision rather than a re-measure.
    private static final double[] PINNED_KNOT_AGES =
        {16.0, 19.0, 22.0, 25.0, 27.0, 30.0, 33.0, 36.0, 40.0};

    // STAGE 1 (ADR 0016), pinned 2026-08-02: every penalty zero. The whole
    // mechanism is wired - the curve is built, every player on the pitch is
    // aged, and lineup strength is P - D - but D is zero everywhere, so the
    // replay is byte-identical to the one before it and the log-loss is
    // unchanged. That is the stage 1 gate, and it is met by construction.
    //
    // STAGE 2 fits these on the WIDE SPINE (ADR 0013), after item 30's third
    // pass, and not before: thirteen years against a twenty-year career means
    // almost nobody in today's window has both ends of his career in the data,
    // so a curve fitted now would be survivorship at both ends. The fit joins
    // the single re-measure ADR 0013 schedules.
    private static final double[] PINNED_KNOT_PENALTIES =
        {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};

    // What a player with no date of birth is charged. The age term then says
    // nothing about him, which is exactly true - and it is the population
    // average rather than zero so that an unknown player is treated as an
    // ordinary one of unknown age, not as one at his peak. Pinned at 0.0 for
    // stage 1 with the rest of the table; stage 2 measures it as the
    // minutes-weighted mean penalty over the population that does have one.
    //
    // This matters in a way it did not before ADR 0016: EVERY player on the
    // pitch now needs an age, not just the 15-in-17,030 charted ones ADR 0011
    // counted. Measured 2026-08-02 on the designated run of 85,050 matches,
    // 2012-07-09 to 2026-07-06: 40,350 of 95,521 men who appear have a date of
    // birth (42.2%), and they play 2,250,909 of 2,478,286 appearances (90.8%) -
    // so this constant is charged on about a tenth of the pitch time.
    private static final double PINNED_UNKNOWN_BIRTH_DATE_PENALTY = 0.0;

    private final double[] knotAges;
    private final double[] knotPenalties;
    private final double unknownBirthDatePenalty;
    private final Map<Long, LocalDate> birthDates;

    // The shipped curve: the pinned, dated table above over this run's dates
    // of birth. The AgePenalty.NONE arm stays reachable beside it as the
    // mechanism-off cell (ADR 0014), the same way FIELD_PLAYERS_ONLY keeps the
    // arm that lost.
    public static AgeingCurve pinned(Map<Long, LocalDate> birthDates) {
        return new AgeingCurve(PINNED_KNOT_AGES, PINNED_KNOT_PENALTIES,
            PINNED_UNKNOWN_BIRTH_DATE_PENALTY, birthDates);
    }

    // A curve is pinned constants, so a malformed table is a typo in them and
    // fails here rather than quietly rating a whole population off a curve
    // nobody fitted.
    public AgeingCurve(double[] knotAges, double[] knotPenalties,
        double unknownBirthDatePenalty, Map<Long, LocalDate> birthDates) {

        if (knotAges.length != knotPenalties.length) {
            throw new IllegalArgumentException("knot ages and penalties differ in length: "
                + knotAges.length + " vs " + knotPenalties.length);
        }
        if (knotAges.length < 2) {
            throw new IllegalArgumentException("a curve needs at least two knots, got "
                + knotAges.length);
        }
        for (int i = 1; i < knotAges.length; i++) {
            if (knotAges[i] <= knotAges[i - 1]) {
                throw new IllegalArgumentException("knot ages must ascend, but "
                    + knotAges[i - 1] + " is followed by " + knotAges[i]);
            }
        }
        // The glossary's two properties, checked rather than trusted: D is
        // never negative, and D(peak age) = 0. A negative penalty would make a
        // player better than his own peak, which is not a curve this model has
        // a meaning for; a table whose minimum is above zero would move every
        // rating in the population by a constant while claiming to be an age
        // effect. Both are typos in pinned constants, and both are cheap to
        // catch here rather than in a leaderboard three hours later.
        double lowest = Double.MAX_VALUE;
        for (double penalty : knotPenalties) {
            if (penalty < 0.0) {
                throw new IllegalArgumentException(
                    "an age penalty is never negative, got " + penalty);
            }
            lowest = Math.min(lowest, penalty);
        }
        if (lowest != 0.0) {
            throw new IllegalArgumentException(
                "the curve must touch zero at the peak age, but its lowest penalty is "
                    + lowest);
        }
        if (unknownBirthDatePenalty < 0.0) {
            throw new IllegalArgumentException(
                "an age penalty is never negative, got " + unknownBirthDatePenalty);
        }
        this.knotAges = knotAges.clone();
        this.knotPenalties = knotPenalties.clone();
        this.unknownBirthDatePenalty = unknownBirthDatePenalty;
        this.birthDates = birthDates;
    }

    // This match's age term, bound to its kickoff date. Built once per match
    // and read once per player, never per rating read: a player's age cannot
    // change inside a match, and the rating seam is read on every goal and on
    // every lineup-constant segment.
    public AgePenalty at(LocalDate kickoff) {
        return playerId -> {
            LocalDate born = birthDates.get(playerId);
            if (born == null) {
                return unknownBirthDatePenalty;
            }
            return penaltyAt(ageOn(born, kickoff));
        };
    }

    // Rating points below peak at this exact age, sliding between the two
    // knots that bracket it. Outside the fitted range the end values HOLD:
    // the curve is a statement about the ages it was measured over and says
    // nothing beyond them, so extending the last slope would invent a
    // fifteen-year-old prodigy or a negative penalty at 44.
    public double penaltyAt(double ageYears) {
        if (ageYears <= knotAges[0]) {
            return knotPenalties[0];
        }
        int last = knotAges.length - 1;
        if (ageYears >= knotAges[last]) {
            return knotPenalties[last];
        }
        int i = 1;
        while (ageYears > knotAges[i]) {
            i++;
        }
        double span = knotAges[i] - knotAges[i - 1];
        double along = (ageYears - knotAges[i - 1]) / span;
        return knotPenalties[i - 1] + along * (knotPenalties[i] - knotPenalties[i - 1]);
    }

    // ---------------------------------------------------------------- in SQL
    //
    // The same curve as an expression over two columns, because the chart is
    // drawn in SQL and must draw what the replay charged. rating_history stores
    // P, the estimated peak (ADR 0016), so the viewer's thick line is
    // P - D(age that day) - and the only way that line cannot disagree with the
    // model is for the knot table to reach the query from here rather than
    // being written out a second time in it. ImpactIndex.sql exists for exactly
    // this reason and #46 named the drift it prevents.
    //
    // Rendered from the pinned constants rather than written out, so a stage 2
    // fit reaches the page with no second edit. While the table is flat these
    // expressions evaluate to zero everywhere and the drawn line is the stored
    // rating exactly, which is what makes stage 1 byte-identical on the page too.

    // Exact age in years at a date, matching ageOn below term for term: whole
    // years since birth, plus the fraction of the way through the current one,
    // measured birthday to birthday. NULL when the date of birth is - the
    // 11.4% of drawable careers with no players row (#36) - which penaltySql
    // then charges the unknown-date constant.
    public static String ageSql(String dob, String day) {
        // Year boundaries crossed, less one if this year's birthday has not
        // come round yet: date_diff('year', ...) counts boundaries, not
        // birthdays, so 2000-12-31 to 2001-01-01 is one to it and zero to us.
        String years = "(date_diff('year', " + dob + ", " + day + ") - CASE WHEN "
            + dob + " + to_years(date_diff('year', " + dob + ", " + day + ")::INTEGER) > " + day
            + " THEN 1 ELSE 0 END)";
        String lastBirthday = "(" + dob + " + to_years(" + years + "::INTEGER))";
        String nextBirthday = "(" + dob + " + to_years((" + years + " + 1)::INTEGER))";
        return "CASE WHEN " + dob + " IS NULL THEN NULL"
            + " WHEN " + day + " < " + dob + " THEN 0.0"
            + " ELSE " + years + " + date_diff('day', " + lastBirthday + ", " + day + ")::DOUBLE"
            + " / date_diff('day', " + lastBirthday + ", " + nextBirthday + ") END";
    }

    // Rating points below peak for a player of this age, over the pinned table.
    //
    // Every man is charged the FIELD curve, Goalkeepers included, because that
    // is what the replay charges them: ADR 0016 stage 1 does not honour the
    // Goalkeeper split, and a chart drawn against a curve the model never used would be a
    // picture of nothing. The results file records who is a Goalkeeper (#22) and
    // the page carries the tag, so when #44's stage 2 fit lands, that table
    // and the branch that selects it land together - here and in freeze, not in
    // the query.
    public String penaltySql(String age) {
        StringBuilder sql = new StringBuilder("CASE");
        sql.append(" WHEN ").append(age).append(" IS NULL THEN ")
            .append(number(unknownBirthDatePenalty));
        // Outside the fitted range the end values hold, exactly as penaltyAt
        // does: the curve says nothing about ages it was not measured over.
        sql.append(" WHEN ").append(age).append(" <= ").append(number(knotAges[0]))
            .append(" THEN ").append(number(knotPenalties[0]));
        int last = knotAges.length - 1;
        sql.append(" WHEN ").append(age).append(" >= ").append(number(knotAges[last]))
            .append(" THEN ").append(number(knotPenalties[last]));
        for (int i = 1; i <= last; i++) {
            double span = knotAges[i] - knotAges[i - 1];
            double rise = knotPenalties[i] - knotPenalties[i - 1];
            sql.append(" WHEN ").append(age).append(" <= ").append(number(knotAges[i]))
                .append(" THEN ").append(number(knotPenalties[i - 1]))
                .append(" + (").append(age).append(" - ").append(number(knotAges[i - 1]))
                .append(") * ").append(number(rise / span));
        }
        return sql.append(" END").toString();
    }

    // Enough digits that a knot reads as the number it was pinned as, and no
    // exponent: DuckDB parses 1.0E-4 but a reader comparing the query to the
    // ADR should not have to.
    private static String number(double d) {
        return new java.math.BigDecimal(d).setScale(10, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    // Exact age in years: whole years since birth, plus the fraction of the
    // way through the current one. Measured birthday to birthday rather than
    // in days over 365.2425, so a man is exactly 26.0 on his 26th birthday
    // whatever the leap years did, and 29 February clamps to 28 February the
    // way LocalDate already clamps it.
    private static double ageOn(LocalDate born, LocalDate day) {
        if (day.isBefore(born)) {
            return 0.0;     // no such player; the curve holds at its first knot anyway
        }
        int years = Period.between(born, day).getYears();
        LocalDate lastBirthday = born.plusYears(years);
        LocalDate nextBirthday = born.plusYears(years + 1L);
        double intoTheYear = ChronoUnit.DAYS.between(lastBirthday, day);
        double yearLength = ChronoUnit.DAYS.between(lastBirthday, nextBirthday);
        return years + intoTheYear / yearLength;
    }
}
