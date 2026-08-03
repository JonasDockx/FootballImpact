package com.goalimpact.engine;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgeingCurveTest {

    // A deliberately steep test curve: nothing like the real one, and nothing
    // the pinned table has to agree with. Peak at 26, where the penalty is 0.
    private static final double[] AGES = {18.0, 26.0, 34.0};
    private static final double[] PENALTIES = {8.0, 0.0, 4.0};

    private static AgeingCurve curve(Map<Long, LocalDate> births) {
        return new AgeingCurve(AGES, PENALTIES, 3.0, births);
    }

    @Test
    void readsTheKnotsExactly() {
        AgeingCurve curve = curve(Map.of());

        assertEquals(8.0, curve.penaltyAt(18.0), 1e-12);
        assertEquals(0.0, curve.penaltyAt(26.0), 1e-12);
        assertEquals(4.0, curve.penaltyAt(34.0), 1e-12);
    }

    @Test
    void slidesLinearlyBetweenKnots() {
        AgeingCurve curve = curve(Map.of());

        assertEquals(4.0, curve.penaltyAt(22.0), 1e-12);   // halfway 18 -> 26
        assertEquals(1.0, curve.penaltyAt(28.0), 1e-12);   // a quarter of 26 -> 34
        assertEquals(3.0, curve.penaltyAt(32.0), 1e-12);
    }

    // The curve is a statement about the ages it was fitted over and says
    // nothing beyond them, so it holds its end values rather than extending
    // the last slope into a 15-year-old with a negative penalty.
    @Test
    void holdsItsEndValuesOutsideTheFittedRange() {
        AgeingCurve curve = curve(Map.of());

        assertEquals(8.0, curve.penaltyAt(15.0), 1e-12);
        assertEquals(4.0, curve.penaltyAt(41.0), 1e-12);
    }

    // The reason #21's "a year is birthday to birthday" problem dissolves:
    // there are no year bins, so the day before a birthday and the day after
    // differ by a day's worth of curve and nothing else.
    @Test
    void readsExactAgeOnTheDayNotWholeYears() {
        Map<Long, LocalDate> births = Map.of(7L, LocalDate.of(2000, 6, 15));
        AgeingCurve curve = curve(births);

        double dayBefore = curve.at(LocalDate.of(2018, 6, 14)).forPlayer(7);
        double birthday = curve.at(LocalDate.of(2018, 6, 15)).forPlayer(7);

        assertEquals(8.0, birthday, 1e-12);              // exactly 18
        assertEquals(8.0, dayBefore, 1e-12);             // a day younger: held at the first knot
        // Halfway between 18 and 26 is 22, so a 22-year-old carries half of 8 -
        // exactly, on his birthday, leap years and all.
        assertEquals(4.0, curve.at(LocalDate.of(2022, 6, 15)).forPlayer(7), 1e-12);
        // And half a year later he is half a knot-step further down: 22.5 on
        // the curve, which is 8 * (1 - 4.5/8) = 3.5.
        assertEquals(3.5, curve.at(LocalDate.of(2022, 12, 15)).forPlayer(7), 2e-3);
    }

    // #21: a missing date of birth gets the population-average penalty, so the
    // age term says nothing about him - which is exactly true.
    @Test
    void unknownDateOfBirthGetsThePopulationAveragePenalty() {
        AgeingCurve curve = curve(Map.of(7L, LocalDate.of(2000, 6, 15)));

        assertEquals(3.0, curve.at(LocalDate.of(2018, 6, 15)).forPlayer(999), 1e-12);
    }

    // Stage 1's pinned table (ADR 0016): the whole mechanism, every penalty
    // zero, so lineup strength is the stored rating and the replay is
    // byte-identical to the one before it.
    @Test
    void thePinnedCurveIsFlatAtZero() {
        AgeingCurve pinned = AgeingCurve.pinned(Map.of(7L, LocalDate.of(2000, 6, 15)));

        assertEquals(0.0, pinned.penaltyAt(18.0), 0.0);
        assertEquals(0.0, pinned.penaltyAt(27.3), 0.0);
        assertEquals(0.0, pinned.penaltyAt(38.0), 0.0);
        assertEquals(0.0, pinned.at(LocalDate.of(2018, 6, 15)).forPlayer(7), 0.0);
        assertEquals(0.0, pinned.at(LocalDate.of(2018, 6, 15)).forPlayer(999), 0.0);
    }

    // A curve is pinned, dated constants; a malformed table is a typo in them,
    // and it fails at construction rather than silently rating players off a
    // curve nobody fitted.
    @Test
    void rejectsAMalformedKnotTable() {
        assertThrows(IllegalArgumentException.class,
            () -> new AgeingCurve(new double[] {18.0, 26.0}, new double[] {1.0}, 0.0, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new AgeingCurve(new double[] {26.0, 18.0}, new double[] {0.0, 8.0}, 0.0, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new AgeingCurve(new double[] {26.0}, new double[] {0.0}, 0.0, Map.of()));
    }

    // The glossary's two properties of an ageing curve, held here: it is never
    // negative, and it touches zero at the peak age.
    @Test
    void rejectsACurveThatIsNotAnAgeingCurve() {
        assertThrows(IllegalArgumentException.class, () ->
            new AgeingCurve(AGES, new double[] {8.0, -1.0, 4.0}, 0.0, Map.of()),
            "a negative penalty makes a player better than his own peak");
        assertThrows(IllegalArgumentException.class, () ->
            new AgeingCurve(AGES, new double[] {8.0, 2.0, 4.0}, 0.0, Map.of()),
            "a curve that never reaches zero is a level shift wearing an age effect");
        assertThrows(IllegalArgumentException.class, () ->
            new AgeingCurve(AGES, PENALTIES, -1.0, Map.of()),
            "and the unknown-age constant is an age penalty like any other");
    }

    // ------------------------------------------------------------- in SQL
    //
    // The contract ADR 0016 put on #22: the viewer draws P - D(age that day),
    // so the curve reaches the chart's query from HERE. These tests are the
    // reason that is safe - the SQL and the Java are checked against each other
    // over the steep test curve, where an interpolation error is visible, and
    // not only over the flat pinned one, where every mistake reads as zero.

    @Test
    void theSqlCurveAgreesWithTheJavaOneAtEveryAge() throws Exception {
        AgeingCurve curve = curve(Map.of());

        for (double age = 12.0; age <= 46.0; age += 0.25) {
            assertEquals(curve.penaltyAt(age),
                evaluate(curve.penaltySql(String.valueOf(age))), 1e-9, "at age " + age);
        }
    }

    @Test
    void theSqlCurveChargesTheUnknownConstantWithNoDateOfBirth() throws Exception {
        AgeingCurve curve = curve(Map.of());

        assertEquals(3.0, evaluate(curve.penaltySql("NULL")), 1e-12);
        assertEquals(3.0, evaluate(curve.penaltySql(
            AgeingCurve.ageSql("CAST(NULL AS DATE)", "DATE '2018-06-15'"))), 1e-12);
    }

    // Exact age, computed the same way on both sides - the fraction of the way
    // from one birthday to the next, so a leap-year baby is the age LocalDate
    // says he is and not a day either side of it.
    @Test
    void theSqlAgeAgreesWithTheJavaOne() throws Exception {
        LocalDate[] births = {
            LocalDate.of(2000, 6, 15), LocalDate.of(2000, 2, 29),
            LocalDate.of(1999, 12, 31), LocalDate.of(1996, 1, 1)};
        LocalDate[] days = {
            LocalDate.of(2018, 6, 14), LocalDate.of(2018, 6, 15), LocalDate.of(2018, 6, 16),
            LocalDate.of(2021, 2, 28), LocalDate.of(2024, 2, 29), LocalDate.of(2022, 12, 15)};

        for (LocalDate born : births) {
            AgeingCurve curve = curve(Map.of(7L, born));
            for (LocalDate day : days) {
                // forPlayer reads the Java age through the very same table, so
                // agreeing here is agreeing on the age and on the curve at once.
                assertEquals(curve.at(day).forPlayer(7),
                    evaluate(curve.penaltySql(
                        AgeingCurve.ageSql("DATE '" + born + "'", "DATE '" + day + "'"))),
                    1e-9, born + " on " + day);
            }
        }
    }

    private static double evaluate(String expression) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + expression)) {
            rs.next();
            return rs.getDouble(1);
        }
    }
}
