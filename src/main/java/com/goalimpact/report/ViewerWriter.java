package com.goalimpact.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Locale;
import java.util.StringJoiner;

// The career-chart viewer (#22): one HTML page carrying every player past the
// eligibility threshold, their careers sampled monthly.
//
// Run separately from the replay (#46). It attaches the results file and the
// vendor snapshot read-only and replays nothing, so rebuilding the page after a
// threshold, band or constant change costs seconds instead of a full run. The
// page itself is repo source - src/main/resources/viewer - and only the filled
// copy is generated; this class reads the template and substitutes the data and
// the pinned constants into it.
//
// Written atomically, because a half-written HTML page still renders, just
// wrongly.
public final class ViewerWriter {

    // The template's data block, named here because the test reads the page
    // back through the same two markers rather than by guessing at the HTML.
    public static final String DATA_PREFIX = "window.DATA=";
    public static final String DATA_SUFFIX = ";/*end-data*/";

    private static final String TEMPLATE = "/viewer/goalimpact-viewer.html";

    // Sampling: one point per calendar month of a career, the last rating the
    // month ended on. Months are absolute (year*12 + month-1) rather than
    // counted from an epoch year, so widening the spine backwards (ADR 0013)
    // moves no axis.
    private static final String MONTH = "(year(match_date)*12 + month(match_date) - 1)";

    public record Result(int players, long points, String runId, LocalDate lastMatchDate, long bytes) {
    }

    private ViewerWriter() {
    }

    public static Result write(Path results, Path snapshot, Path out)
        throws SQLException, IOException {

        String template = template();
        String data;
        int players;
        long points;
        String runId;
        LocalDate lastMatch;

        // In-memory connection with both files attached read-only: neither the
        // disposable results DB nor the precious snapshot is opened for writing
        // by a step whose only job is to read them.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {

            s.execute("ATTACH '" + literal(results) + "' AS r (READ_ONLY)");
            s.execute("ATTACH '" + literal(snapshot) + "' AS tm (READ_ONLY)");

            try (ResultSet rs = s.executeQuery(
                "SELECT count(DISTINCT run_id) AS runs, any_value(run_id) AS run_id,"
                + " max(match_date) AS last_match FROM r.rating_history")) {
                rs.next();
                int runs = rs.getInt("runs");
                if (runs != 1) {
                    // The history is dropped and rewritten by every designated
                    // run, so more than one run id in it means the file is not
                    // what it claims to be and the page must not quote it.
                    throw new IllegalStateException(
                        "rating_history holds " + runs + " run ids; expected exactly one");
                }
                runId = rs.getString("run_id");
                lastMatch = rs.getDate("last_match").toLocalDate();
            }

            build(s);

            try (ResultSet rs = s.executeQuery(
                "SELECT count(*) AS players, coalesce(sum(len(vs)), 0) AS points FROM page")) {
                rs.next();
                players = rs.getInt("players");
                points = rs.getLong("points");
            }

            data = json(s);
        }

        String filled = template
            .replace("{{DATA}}", data)
            .replace("{{CONSTANTS}}", constants())
            .replace("{{RUN_ID}}", escape(runId))
            .replace("{{LAST_MATCH_DATE}}", lastMatch.toString())
            .replace("{{PLAYER_COUNT}}", String.format(Locale.US, "%,d", players));

        byte[] bytes = filled.getBytes(StandardCharsets.UTF_8);
        writeAtomically(out, bytes);
        return new Result(players, points, runId, lastMatch, bytes.length);
    }

    // The query the prototype's idx.sql and build.sql worked out (#35), moved
    // into Java so the two things it shares with RatingHistoryWriter - the
    // column names and the Impact index rescale - are held by one test.
    private static void build(Statement s) throws SQLException {
        // rating_after is already the running Value at that match, so a career
        // needs no window function to be correct (ADR 0011). The one window
        // here is the running exposure, which decides where a line may start.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE career AS
              SELECT player_id, match_id, match_date,
                     %s AS idx,
                     sum(minutes_played) OVER (
                         PARTITION BY player_id ORDER BY match_date, match_id) AS cum_min
              FROM r.rating_history""".formatted(ImpactIndex.sql("rating_after")));

        s.execute("""
            CREATE OR REPLACE TEMP TABLE total AS
              SELECT player_id, sum(minutes_played) AS mins, count(*) AS apps,
                     min(match_date) AS first_d, max(match_date) AS last_d
              FROM r.rating_history GROUP BY player_id""");

        // #35: names come from game_lineups, never from players. Lineups name
        // 94,902 of 95,521 rated players against 40,364 with a players row;
        // players is the identity card (date of birth, position), not the name
        // source. 1,038 ids carry more than one spelling, so one is taken.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE named AS
              SELECT player_id, any_value(player_name) AS name
              FROM tm.game_lineups WHERE player_name IS NOT NULL GROUP BY player_id""");

        s.execute("""
            CREATE OR REPLACE TEMP TABLE last_club AS
              SELECT gl.player_id, any_value(c.name ORDER BY gl.date DESC) AS club
              FROM tm.game_lineups gl JOIN tm.clubs c ON c.club_id = gl.club_id
              GROUP BY gl.player_id""");

        // The eligible population, and the only one the page draws: ADR 0011's
        // 1,000 career minutes, which is also the population the index
        // constants were measured over.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE eligible AS
            SELECT t.player_id AS id, n.name AS name,
                   lc.club, p.position, p.date_of_birth::date AS dob,
                   t.mins::int AS mins, t.apps,
                   year(t.first_d) AS y0, year(t.last_d) AS y1,
                   round((SELECT max(idx) FROM career c
                          WHERE c.player_id = t.player_id AND c.cum_min >= %d), 1) AS peak,
                   round((SELECT idx FROM career c WHERE c.player_id = t.player_id
                          ORDER BY match_date DESC, match_id DESC LIMIT 1), 1) AS latest
            FROM total t JOIN named n USING (player_id)
                 LEFT JOIN last_club lc ON lc.player_id = t.player_id
                 LEFT JOIN tm.players p ON p.player_id = t.player_id
            WHERE t.mins >= %d""".formatted(
                ImpactIndex.ELIGIBLE_MINUTES, ImpactIndex.ELIGIBLE_MINUTES));

        // A line starts where the exposure does, so the sampled career carries
        // only the part of it the threshold admits.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE monthly AS
              SELECT player_id, %s AS m, round(last(idx ORDER BY match_date, match_id), 1) AS v
              FROM career
              WHERE cum_min >= %d AND player_id IN (SELECT id FROM eligible)
              GROUP BY 1, 2""".formatted(MONTH, ImpactIndex.ELIGIBLE_MINUTES));

        s.execute("""
            CREATE OR REPLACE TEMP TABLE series AS
              SELECT player_id, list(m ORDER BY m) AS ms, list(v ORDER BY m) AS vs
              FROM monthly GROUP BY player_id""");

        // dobm is the date of birth on the same absolute month axis, so the age
        // axis is (m - dobm)/12. NULL for the 11.4% with no players row: they
        // get a line and no age axis rather than a wrong one (#36, #40).
        s.execute("""
            CREATE OR REPLACE TEMP TABLE page AS
              SELECT e.id, e.name, coalesce(e.club, '') AS club,
                     coalesce(e.position, '') AS pos,
                     e.mins, e.apps, e.y0, e.y1, e.peak, e.latest, s.ms, s.vs,
                     CASE WHEN e.dob IS NOT NULL
                          THEN year(e.dob)*12 + month(e.dob) - 1 END AS dobm
              FROM eligible e JOIN series s ON s.player_id = e.id""");
    }

    // One JSON object per row, streamed into an array rather than collected
    // into objects first: this is 600,000 points today and grows with the
    // spine, and nothing in between needs to understand a career.
    private static String json(Statement s) throws SQLException {
        StringJoiner rows = new StringJoiner(",\n", "[\n", "\n]");
        try (ResultSet rs = s.executeQuery(
            "SELECT to_json(page) AS row FROM page ORDER BY peak DESC NULLS LAST")) {
            while (rs.next()) {
                rows.add(rs.getString("row"));
            }
        }
        return rows.toString();
    }

    // Every pinned number the chart draws with, handed to the page rather than
    // written into it, so ADR 0011's scale and #47's ladder are edited in Java
    // once and nowhere else.
    private static String constants() {
        StringJoiner ticks = new StringJoiner(",", "[", "]");
        for (RankLadder.Tick t : RankLadder.TICKS) {
            ticks.add("{\"index\":" + number(t.index()) + ",\"label\":\"" + escape(t.label()) + "\"}");
        }
        return "{"
            + "\"CENTRE\":" + number(ImpactIndex.CENTRE)
            + ",\"POINTS_PER_SD\":" + number(ImpactIndex.POINTS_PER_SD)
            + ",\"VALUE_MEAN\":" + number(ImpactIndex.VALUE_MEAN)
            + ",\"VALUE_SD\":" + number(ImpactIndex.VALUE_SD)
            + ",\"ELIGIBLE_MINUTES\":" + ImpactIndex.ELIGIBLE_MINUTES
            + ",\"TICKS\":" + ticks
            + ",\"RANK_POOL\":" + RankLadder.POOL
            + ",\"RANK_MEASURED\":\"" + escape(RankLadder.MEASURED) + "\""
            + ",\"RANK_MATCHES_THROUGH\":\"" + escape(RankLadder.MATCHES_THROUGH) + "\""
            + "}";
    }

    private static String number(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // JSON string escaping for the handful of short strings this class emits
    // itself - the population's own rows come out of DuckDB already encoded.
    // Control characters included: a run id is a format string today, but the
    // page must not be breakable by whatever ends up in one.
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    // DuckDB string literal for a path. Forward slashes because a Windows
    // backslash is an escape to nobody here but reads badly in a log.
    private static String literal(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static String template() throws IOException {
        try (InputStream in = ViewerWriter.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IOException("viewer template missing from the classpath: " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // Temp file beside the target, then rename: the reader either sees the old
    // page or the new one, never half of either.
    private static void writeAtomically(Path out, byte[] bytes) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            try {
                Files.move(tmp, out,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Same directory, so this should not happen; a filesystem that
                // cannot rename atomically still leaves the old page intact
                // until the move, which is the property that matters.
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Either move failing leaves the half-built page behind, and a
            // stray .tmp beside the real one is exactly the confusion the
            // temp-then-rename was meant to avoid.
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
