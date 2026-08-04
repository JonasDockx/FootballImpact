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

    // #23's club dictionary, read back the same way for the same reason.
    public static final String CLUBS_PREFIX = "window.CLUBS=";
    public static final String CLUBS_SUFFIX = ";/*end-clubs*/";

    private static final String TEMPLATE = "/viewer/goalimpact-viewer.html";

    // Sampling: one point per calendar month of a career, the last rating the
    // month ended on. Months are absolute (year*12 + month-1) rather than
    // counted from an epoch year, so widening the spine backwards (ADR 0013)
    // moves no axis.
    private static final String MONTH = "(year(match_date)*12 + month(match_date) - 1)";

    public record Result(int players, long points, long bands, String runId,
                         LocalDate lastMatchDate, long bytes) {
    }

    private ViewerWriter() {
    }

    public static Result write(Path results, Path snapshot, Path out)
        throws SQLException, IOException {

        String template = template();
        String data;
        String clubs;
        int players;
        long points;
        long bands;
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
                "SELECT count(*) AS players, coalesce(sum(len(vs)), 0) AS points,"
                + " coalesce(sum(len(sm)), 0) AS bands FROM page")) {
                rs.next();
                players = rs.getInt("players");
                points = rs.getLong("points");
                bands = rs.getLong("bands");
            }

            data = json(s);
            clubs = clubs(s);
        }

        String filled = template
            .replace("{{DATA}}", data)
            .replace("{{CLUBS}}", clubs)
            .replace("{{CONSTANTS}}", constants())
            .replace("{{RUN_ID}}", Json.escape(runId))
            .replace("{{LAST_MATCH_DATE}}", lastMatch.toString())
            .replace("{{PLAYER_COUNT}}", String.format(Locale.US, "%,d", players));

        byte[] bytes = filled.getBytes(StandardCharsets.UTF_8);
        AtomicWrite.toFile(out, bytes);
        return new Result(players, points, bands, runId, lastMatch, bytes.length);
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

        // The matches that are somebody's country rather than somebody's club,
        // named once as a table because two surfaces read the decision and a
        // predicate written out twice is two chances for the id card's chip and
        // the band under it to disagree about where a man was that afternoon.
        //
        // The competition is what says so, and it has to be: the fixture list
        // names a national side exactly as it names a club, so club_name resolves
        // "Argentina" perfectly happily. Reading tm.clubs alone would have
        // dropped the caps by accident - no national side has a squad page - and
        // an accident is not a decision.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE cap_match AS
              SELECT TRY_CAST(g.game_id AS BIGINT) AS match_id
              FROM tm.games g
                   JOIN tm.competitions comp ON comp.competition_id = g.competition_id
              WHERE comp.type = 'national_team_competition'
                AND TRY_CAST(g.game_id AS BIGINT) IS NOT NULL""");

        // The id card's club chip: the last club a lineup put him in, off
        // ResultsFile's club_name so the chip and the band under the chart call
        // a club the same thing. Over his whole record rather than the drawn
        // stretch, which is why it can name a club where the chart under it has
        // no band - the bands are cut from matches the run RATED.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE last_club AS
              SELECT gl.player_id, any_value(c.name ORDER BY gl.date DESC) AS club
              FROM tm.game_lineups gl
                   JOIN club_name c ON c.club_id = gl.club_id
              WHERE TRY_CAST(gl.game_id AS BIGINT) NOT IN (SELECT match_id FROM cap_match)
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

        tenures(s);

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
                     coalesce(t.sm, []::BIGINT[]) AS sm,
                     coalesce(t.sc, []::BIGINT[]) AS sc,
                     CASE WHEN e.dob IS NOT NULL
                          THEN year(e.dob)*12 + month(e.dob) - 1 END AS dobm
              FROM eligible e JOIN series s ON s.player_id = e.id
                   LEFT JOIN player_bands t ON t.player_id = e.id""");
    }

    // #23's Tenure bands: where the player was, cut from the same rows the match
    // log prints a club on (ResultsFile's his_club), so a transfer sits at one
    // date on both surfaces.
    //
    // A Tenure is a maximal run of CONSECUTIVE rated matches at one club, and
    // nothing is smoothed. Measured 2026-08-04 on the designated run: 20.6% of
    // its 83,069 tenures are one or two matches long, but only 766 of those are
    // sandwiched between two runs at the same club - so the short ones are
    // overwhelmingly real (a January move, a cup tie for the parent club during
    // a loan), and a merge rule would be the page asserting something the record
    // does not say. Loans need no case of their own for the same reason: a band
    // says where he PLAYED, not who held his registration, so a loan is a tenure
    // like any other and a return is a second tenure at the first club.
    private static void tenures(Statement s) throws SQLException {
        // Only matches with a NAMED club open or close a tenure, and a
        // national-team cap never does (cap_match above) - a country is not a
        // club, and a cap mid-season would otherwise cut a club run into three.
        //
        // Through his_club rather than straight off the lineups, so the club a
        // band names for a match is the same one the match log prints on that
        // row: a handful of fixtures carry more than one lineup row for a player
        // and the choice between them is made once, in ResultsFile.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE club_match AS
              SELECT c.player_id, c.match_id, c.match_date, cl.name AS club,
                     row_number() OVER (
                         PARTITION BY c.player_id ORDER BY c.match_date, c.match_id) AS rn
              FROM career c
                   JOIN eligible e ON e.id = c.player_id
                   JOIN his_club hc
                        ON hc.player_id = c.player_id AND hc.match_id = c.match_id
                   JOIN club_name cl ON cl.club_id = hc.club_id
              WHERE c.match_id NOT IN (SELECT match_id FROM cap_match)""");

        // The gaps-and-islands grouping: rn less the row number within one
        // club is constant exactly along a contiguous run at that club.
        // Tenures are measured over the WHOLE career, not the drawn stretch,
        // because the club a man was at when the chart opens is nearly always
        // one he joined before the 1,000th minute.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE tenure AS
              SELECT player_id, club, min(rn) AS ord,
                     min(%1$s) AS m0, max(%1$s) AS m1
              FROM (SELECT *, rn - row_number() OVER (
                        PARTITION BY player_id, club ORDER BY rn) AS run
                    FROM club_match)
              GROUP BY player_id, club, run""".formatted(MONTH));

        s.execute("""
            CREATE OR REPLACE TEMP TABLE drawn_window AS
              SELECT player_id, min(m) AS m0, max(m) AS m1 FROM monthly GROUP BY player_id""");

        // Bands TILE the drawn stretch: a band runs from its own first rated
        // match to the next band's, and the last runs to the end of the line.
        // A gap of months where he played nobody is therefore absorbed into the
        // band before it, which is the honest reading - an injured man has not
        // left his club - and it means the boundary a reader sees is always "his
        // first recorded match for the new club" rather than a transfer date this
        // project does not hold.
        //
        // Clipped at the chart's own first month, which can push two tenures onto
        // the same starting month; the later one wins, because it is the club he
        // was at when the line begins. Ordered by career position rather than by
        // month for that reason - two tenures can share a month, and never a
        // place in the sequence.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE band AS
              SELECT player_id, club, m FROM (
                SELECT t.player_id, t.club, t.ord, greatest(t.m0, w.m0) AS m,
                       lead(greatest(t.m0, w.m0)) OVER (
                           PARTITION BY t.player_id ORDER BY t.ord) AS next_m
                FROM tenure t JOIN drawn_window w USING (player_id)
                WHERE t.m1 >= w.m0 AND t.m0 <= w.m1)
              WHERE next_m IS NULL OR next_m > m""");

        // Named once for the page and referenced by index, for LedgerWriter's
        // reason: 1,879 club names repeat across 62,733 bands, and the repetition
        // costs more than the dictionary. One dictionary for the whole page,
        // where a shard needs its own, because the page is loaded whole.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE club_ref AS
              SELECT club, row_number() OVER (ORDER BY club) - 1 AS ci
              FROM (SELECT DISTINCT club FROM band)""");

        s.execute("""
            CREATE OR REPLACE TEMP TABLE player_bands AS
              SELECT b.player_id, list(b.m ORDER BY b.m) AS sm,
                     list(r.ci ORDER BY b.m) AS sc
              FROM band b JOIN club_ref r USING (club) GROUP BY b.player_id""");
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

    // The band dictionary, in index order - so CLUBS[sc[i]] is the club, and the
    // order is the one club_ref numbered rather than whatever the page happens to
    // read back.
    private static String clubs(Statement s) throws SQLException {
        StringJoiner names = new StringJoiner(",", "[", "]");
        try (ResultSet rs = s.executeQuery("SELECT club FROM club_ref ORDER BY ci")) {
            while (rs.next()) {
                names.add('"' + Json.escape(rs.getString("club")) + '"');
            }
        }
        return names.toString();
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
            // #48's burn-in ramp: where it starts, where it ends, and how pale
            // it is allowed to get. Pinned in Java like everything above it -
            // the page never works a boundary out for itself (#46's fence).
            + ",\"BURNIN_FROM\":" + BurnIn.FROM_MONTH
            + ",\"BURNIN_BOUNDARY\":" + BurnIn.BOUNDARY_MONTH
            + ",\"BURNIN_LABEL\":\"" + Json.escape(BurnIn.BOUNDARY) + "\""
            + ",\"BURNIN_FLOOR\":" + number(BurnIn.OPACITY_FLOOR)
            + ",\"BURNIN_MEASURED\":\"" + Json.escape(BurnIn.MEASURED) + "\""
            + ",\"BURNIN_POOL\":" + BurnIn.POOL_AT_BOUNDARY
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
