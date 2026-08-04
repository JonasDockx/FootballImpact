package com.goalimpact.report;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

// The results file and the vendor snapshot, opened the one way every build step
// opens them, and the facts both steps have to agree about.
//
// It exists because #46's one-contract rule bites twice over as soon as there
// are two build steps (#22's page and #24's match log). They read the same
// file, must quote the same run, must refuse the same broken files, and must
// draw the same population - and a chart drawn over one eligible population
// beside a log listing another is the exact failure the rule exists to stop.
// Held here rather than in either writer, so neither owns it.
//
// Not a general database wrapper: it knows the four things both steps need and
// hands out the Statement for everything else. Both files are attached
// READ_ONLY - neither the disposable results DB nor the precious snapshot is
// opened for writing by a step whose only job is to read them.
final class ResultsFile implements AutoCloseable {

    private final Connection connection;
    private final Statement statement;
    private final String runId;
    private final LocalDate lastMatchDate;

    private ResultsFile(Connection connection, Statement statement,
                        String runId, LocalDate lastMatchDate) {
        this.connection = connection;
        this.statement = statement;
        this.runId = runId;
        this.lastMatchDate = lastMatchDate;
    }

    static ResultsFile open(Path results, Path snapshot) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try {
            Statement s = c.createStatement();
            s.execute("ATTACH '" + literal(results) + "' AS r (READ_ONLY)");
            s.execute("ATTACH '" + literal(snapshot) + "' AS tm (READ_ONLY)");

            String runId;
            LocalDate lastMatch;
            try (ResultSet rs = s.executeQuery(
                "SELECT count(DISTINCT run_id) AS runs, any_value(run_id) AS run_id,"
                + " max(match_date) AS last_match FROM r.rating_history")) {
                rs.next();
                int runs = rs.getInt("runs");
                if (runs != 1) {
                    // The history is dropped and rewritten by every designated
                    // run, so more than one run id in it means the file is not
                    // what it claims to be and no step may quote it.
                    throw new IllegalStateException(
                        "rating_history holds " + runs + " run ids; expected exactly one");
                }
                runId = rs.getString("run_id");
                lastMatch = rs.getDate("last_match").toLocalDate();
            }
            requireCareerTags(s, runId);
            // Built here rather than left for the caller to remember: both
            // steps need the same three tables and there is no useful state in
            // which a ResultsFile is open without them.
            defineEligible(s);
            return new ResultsFile(c, s, runId, lastMatch);
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    Statement statement() {
        return statement;
    }

    String runId() {
        return runId;
    }

    LocalDate lastMatchDate() {
        return lastMatchDate;
    }

    // The five temp tables both steps read: dates of birth, career totals, the
    // population ADR 0011 admits, which club a man played for in a match, and
    // what that club is called. Created here so the 1,000-minute threshold is
    // applied by one query rather than by two that agree today.
    private static void defineEligible(Statement statement) throws SQLException {
        // The dates of birth the age term is read at. TRY_CAST, matching
        // TransfermarktLoader.birthDates(), so a malformed vendor date is an
        // unknown date of birth here exactly as it is in the replay.
        //
        // One seam is open and worth naming: the replay lets ADR 0012's sidecar
        // register overrule the vendor for a hand-typed player, and these steps
        // attach only the snapshot. It cannot bite while the curve is pinned
        // flat - every penalty is zero, so no date of birth changes a drawn
        // point or a logged one - and it closes with the sidecar when the curve
        // is fitted. Named in one place now that two steps read it.
        statement.execute("""
            CREATE OR REPLACE TEMP TABLE born AS
              SELECT player_id, TRY_CAST(date_of_birth AS DATE) AS dob FROM tm.players""");

        statement.execute("""
            CREATE OR REPLACE TEMP TABLE total AS
              SELECT player_id, sum(minutes_played) AS mins, count(*) AS apps,
                     min(match_date) AS first_d, max(match_date) AS last_d
              FROM r.rating_history GROUP BY player_id""");

        // ADR 0011's 1,000 career minutes, which is also the population the
        // index constants were measured over. Minutes ALONE - a name is what
        // decides whether the page can draw a man, not whether the model rated
        // him, so the log lists 26,112 careers where the chart draws 25,970 and
        // the 142 without a name simply cannot be clicked.
        statement.execute("""
            CREATE OR REPLACE TEMP TABLE eligible_ids AS
              SELECT player_id FROM total WHERE mins >= %d"""
            .formatted(ImpactIndex.ELIGIBLE_MINUTES));

        // WHICH CLUB HE PLAYED FOR that day, which rating_history does not carry
        // - the run rates a player, not a club. Both steps need it and they must
        // not answer it differently: the log prints a club on every row and the
        // chart draws a Tenure band off the same rows (#23), so two resolutions
        // that disagree would put a transfer at one date under a chart showing it
        // at another.
        //
        // Only the eligible population's rows, so the 3.18M-row scan is joined
        // down before it is grouped. any_value because 1,038 ids carry more than
        // one spelling and a handful more than one club row per fixture (#35);
        // one is taken, the same resolution the page's names take.
        statement.execute("""
            CREATE OR REPLACE TEMP TABLE his_club AS
              SELECT CAST(gl.game_id AS BIGINT) AS match_id, gl.player_id,
                     any_value(gl.club_id) AS club_id
              FROM tm.game_lineups gl
                   JOIN eligible_ids e ON e.player_id = gl.player_id
              GROUP BY 1, 2""");

        clubNames(statement);
    }

    // WHAT A CLUB IS CALLED, from both places the snapshot says so - and the
    // second one is not optional. tm.clubs is a table of CURRENT SQUADS in the
    // covered leagues: 796 rows against the 3,144 clubs that appear in lineups,
    // so a name read from it alone leaves 2,353 clubs anonymous. The fixture
    // list names 3,058 of them, because a club that played a match is named in
    // the row for that match whether or not it has a squad page.
    //
    // Measured 2026-08-04 on the designated run: reading clubs alone left 3,515
    // of the 25,970 drawn careers (13.5%) with no club on record at all -
    // Ferencvaros, Qarabag, every side reached only through a European tie -
    // which the page would then have had to report as "no club recorded" over a
    // record that names it perfectly well. Reading both leaves 36 (0.1%).
    //
    // Squad name first where both exist, because that is the club's own page and
    // the fixture list abbreviates. Names, and nothing else: a club_id still
    // reaches here through his_club or through a fixture, and nothing about a
    // club is stored, rated or updated (the glossary's Strength).
    private static void clubNames(Statement statement) throws SQLException {
        statement.execute("""
            CREATE OR REPLACE TEMP TABLE club_name AS
              SELECT club_id, first(name ORDER BY src, name) AS name FROM (
                SELECT TRY_CAST(club_id AS BIGINT) AS club_id, name, 0 AS src
                FROM tm.clubs
                UNION ALL
                SELECT home_club_id, home_club_name, 1 FROM tm.games
                UNION ALL
                SELECT away_club_id, away_club_name, 1 FROM tm.games)
              WHERE club_id IS NOT NULL AND name IS NOT NULL AND name <> ''
              GROUP BY club_id""");
    }

    // The career tags the run stamped (ADR 0016, #44), demanded rather than
    // worked around. A results file that does not carry them is one no step can
    // honestly draw or log: it would have to decide for itself who is a
    // Goalkeeper, which is the thing the flag exists to stop. So it says so and
    // stops - a page that quietly drew every Goalkeeper as a field player would
    // look exactly like a correct one.
    //
    // Three ways it can be wrong, and all three are checked, because the
    // failure mode is silence in every one of them: no table, a table from
    // another run, and a table that is merely SHORT. The last is the reason
    // this is not one existence check: eligible LEFT JOINs the tags, so a
    // player with no row there is drawn as a field player by default.
    private static void requireCareerTags(Statement s, String runId) throws SQLException {
        try (ResultSet rs = s.executeQuery(
            "SELECT count(*) AS present FROM duckdb_tables()"
            + " WHERE database_name = 'r' AND table_name = 'player_careers'")) {
            rs.next();
            if (rs.getInt("present") == 0) {
                throw new IllegalStateException(
                    "the results file carries no player_careers table, so nothing in it says"
                    + " who is a Goalkeeper (#22, ADR 0016)."
                    + " Re-run the designated replay to write it.");
            }
        }
        try (ResultSet rs = s.executeQuery(
            "SELECT count(DISTINCT run_id) AS runs, any_value(run_id) AS run_id,"
            + " (SELECT count(*) FROM (SELECT player_id FROM r.rating_history"
            + "  EXCEPT SELECT player_id FROM r.player_careers)) AS untagged"
            + " FROM r.player_careers")) {
            rs.next();
            int runs = rs.getInt("runs");
            if (runs != 1) {
                throw new IllegalStateException(
                    "player_careers holds " + runs + " run ids; expected exactly one");
            }
            String tagged = rs.getString("run_id");
            if (!runId.equals(tagged)) {
                // Two runs' halves in one file: the ratings would be drawn
                // against another run's Goalkeepers, which is worse than either
                // half alone.
                throw new IllegalStateException("player_careers was written by run " + tagged
                    + " but rating_history by " + runId);
            }
            long untagged = rs.getLong("untagged");
            if (untagged > 0) {
                throw new IllegalStateException("player_careers is short: " + untagged
                    + " rated players carry no career tag");
            }
        }
    }

    // DuckDB string literal for a path. Forward slashes because a Windows
    // backslash is an escape to nobody here but reads badly in a log.
    private static String literal(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
