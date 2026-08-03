package com.goalimpact.report;

import com.goalimpact.engine.AgeingCurve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
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
// Written atomically (AtomicWrite), because a half-written HTML page still
// renders, just wrongly.
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

        // Opened, guarded and given its population by ResultsFile, which the
        // match log (#24) opens the same way: one definition of which run this
        // is, which files are refused, and who is eligible.
        try (ResultsFile file = ResultsFile.open(results, snapshot)) {
            Statement s = file.statement();
            runId = file.runId();
            lastMatch = file.lastMatchDate();

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
            .replace("{{RUN_ID}}", Json.escape(runId))
            .replace("{{LAST_MATCH_DATE}}", lastMatch.toString())
            .replace("{{PLAYER_COUNT}}", String.format(Locale.US, "%,d", players));

        byte[] bytes = filled.getBytes(StandardCharsets.UTF_8);
        AtomicWrite.toFile(out, bytes);
        return new Result(players, points, runId, lastMatch, bytes.length);
    }

    // The query the prototype's idx.sql and build.sql worked out (#35), moved
    // into Java so the two things it shares with RatingHistoryWriter - the
    // column names and the Impact index rescale - are held by one test. born,
    // total and eligible_ids are already on the connection, from ResultsFile.
    private static void build(Statement s) throws SQLException {
        // rating_after is the stored P, the estimated PEAK (ADR 0016), so the
        // drawn line is P - D(age that day) - not the stored number itself. The
        // curve comes from AgeingCurve, which is the whole point: a second copy
        // of the knot table written in SQL is the drift ImpactIndex exists to
        // prevent, and the chart would then draw a curve the model never
        // charged. Stage 1's table is flat, so this is the identity today.
        //
        // Every man is charged the field curve, Goalkeepers included, because
        // that is what the replay charges; the tag the page carries is read
        // from player_careers below and selects a curve of its own the day
        // #44's stage 2 fit lands.
        //
        // A career needs no window function to be correct (ADR 0011). The one
        // window here is the running exposure, which decides where a line may
        // start.
        // No dates of birth handed over: the page reads its own in SQL, off the
        // born table above, so the curve is wanted here for its knots alone.
        String idx = ImpactIndex.sql("(h.rating_after - (%s))".formatted(
            AgeingCurve.pinned(Map.of())
                .penaltySql(AgeingCurve.ageSql("b.dob", "h.match_date"))));
        s.execute("""
            CREATE OR REPLACE TEMP TABLE career AS
              SELECT h.player_id, h.match_id, h.match_date,
                     %s AS idx,
                     sum(h.minutes_played) OVER (
                         PARTITION BY h.player_id ORDER BY h.match_date, h.match_id) AS cum_min
              FROM r.rating_history h LEFT JOIN born b ON b.player_id = h.player_id"""
            .formatted(idx));

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

        // The population the page DRAWS: ResultsFile's eligible_ids - ADR
        // 0011's 1,000 career minutes - narrowed by the one thing a chart needs
        // and a log does not, a name to put on it. The 142 nameless eligible
        // careers are in the match log and on no axis here (#35's 619, of whom
        // these are the ones past the threshold).
        s.execute("""
            CREATE OR REPLACE TEMP TABLE eligible AS
            SELECT t.player_id AS id, n.name AS name,
                   lc.club, p.position, b.dob,
                   coalesce(pc.goalkeeper, false) AS goalkeeper,
                   t.mins::int AS mins, t.apps,
                   year(t.first_d) AS y0, year(t.last_d) AS y1,
                   round((SELECT max(idx) FROM career c
                          WHERE c.player_id = t.player_id AND c.cum_min >= %d), 1) AS peak,
                   round((SELECT idx FROM career c WHERE c.player_id = t.player_id
                          ORDER BY match_date DESC, match_id DESC LIMIT 1), 1) AS latest
            FROM total t JOIN eligible_ids e USING (player_id)
                 JOIN named n USING (player_id)
                 LEFT JOIN last_club lc ON lc.player_id = t.player_id
                 LEFT JOIN tm.players p ON p.player_id = t.player_id
                 LEFT JOIN born b ON b.player_id = t.player_id
                 LEFT JOIN r.player_careers pc ON pc.player_id = t.player_id"""
            .formatted(ImpactIndex.ELIGIBLE_MINUTES));

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
        // goalkeeper is the RUN's career tag and pos is the VENDOR's position,
        // and they are two fields rather than one on purpose. The tag is the
        // model rated him under - the glossary's career tag, sticky from his
        // first start in goal - and it covers the men the vendor's players table
        // has never heard of, who are more than half the rated population (#35).
        // pos is the only source for the three outfield categories, which the
        // run does not record. Where the two disagree the page shows both rather
        // than picking a winner: a man the vendor lists in goal who never
        // started in goal in a usable match was rated as a field player, and
        // that is a fact about the run worth seeing.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE page AS
              SELECT e.id, e.name, coalesce(e.club, '') AS club,
                     coalesce(e.position, '') AS pos, e.goalkeeper,
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
            ticks.add("{\"index\":" + number(t.index()) + ",\"label\":\"" + Json.escape(t.label()) + "\"}");
        }
        return "{"
            + "\"CENTRE\":" + number(ImpactIndex.CENTRE)
            + ",\"POINTS_PER_SD\":" + number(ImpactIndex.POINTS_PER_SD)
            + ",\"VALUE_MEAN\":" + number(ImpactIndex.VALUE_MEAN)
            + ",\"VALUE_SD\":" + number(ImpactIndex.VALUE_SD)
            + ",\"ELIGIBLE_MINUTES\":" + ImpactIndex.ELIGIBLE_MINUTES
            + ",\"TICKS\":" + ticks
            + ",\"RANK_POOL\":" + RankLadder.POOL
            + ",\"RANK_MEASURED\":\"" + Json.escape(RankLadder.MEASURED) + "\""
            + ",\"RANK_MATCHES_THROUGH\":\"" + Json.escape(RankLadder.MATCHES_THROUGH) + "\""
            // Where the match log is and how it is cut up (#24). The page picks
            // a shard with id % LEDGER_SHARDS, so this is the same decision
            // LedgerWriter made when it wrote them and must not be a second
            // copy of it in the template.
            + ",\"LEDGER_FOLDER\":\"" + Json.escape(LedgerWriter.FOLDER) + "\""
            + ",\"LEDGER_SHARDS\":" + LedgerWriter.SHARDS
            + "}";
    }

    private static String number(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String template() throws IOException {
        try (InputStream in = ViewerWriter.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IOException("viewer template missing from the classpath: " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
