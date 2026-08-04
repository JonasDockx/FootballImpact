package com.goalimpact.report;

import com.goalimpact.engine.AgeingCurve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The match log (#24): for every match an eligible player was on the pitch for,
// what happened, what the model expected, what it charged him, and where his
// rating went. Reached by clicking a player on #22's chart - a drill-down, not
// a second surface.
//
// It is a FOLDER beside the page and that is the whole design, on a measured
// number. The eligible population's log is 2,185,765 rows at 102 bytes each -
// 222 MB written, against the 12 MB page #35 justified embedding whole - and
// ADR 0013's widening multiplies it ~4x. One PLAYER's log is 50 rows (median;
// p90 201, max 686). So the rows ship sharded by player_id % 256 and the page
// loads the one shard it needs, by <script> tag rather than fetch() - fetch is
// blocked on file:// and script tags are not, and "double-click it and it
// works" is the property #22 and #46 both hold.
//
// Those are the sizes this writer PRODUCED, measured 2026-08-03 on the run
// through 2026-07-06. #24's design note estimated 123 bytes and ~270 MB from a
// 20,000-row sample before the writer existed; the estimate is what justified
// the folder and the measurement is what the folder costs, and the two are kept
// distinct rather than one quietly overwriting the other.
//
// Runs as a second step of BuildViewer for the reason the page is a step at
// all (#46): it replays nothing, so rebuilding the log after a threshold or
// constant change costs seconds instead of a run.
//
// Deliberately WEAKER guards than the page's. ViewerWriter refuses a results
// file outright because a missing keeper flag silently changed a drawn line; a
// missing or wrong-run ledger changes nothing drawn, so the page states the
// reason in words and draws the chart anyway. Holding a correct chart hostage
// to a log would be the wrong trade. What is never done is showing another
// run's rows - hence the run id in every shard, empty ones included.
public final class LedgerWriter {

    // The shard's two markers, named here because the test reads a shard back
    // through them rather than by guessing at the JavaScript - the same reason
    // ViewerWriter names the page's.
    public static final String PAYLOAD_PREFIX = "GI_LEDGER(";
    public static final String PAYLOAD_SUFFIX = ");";

    // player_id % 256. Chosen over a smaller count because a shard is loaded
    // whole to read one career: 256 puts a shard at ~870 KB.
    //
    // Handed to the page through ViewerWriter's constants block rather than
    // written into the template, for the reason every other pinned number is:
    // the page computes id % SHARDS to pick a file, and a template carrying its
    // own 256 would be a second copy of this decision that nothing checks.
    public static final int SHARDS = 256;

    // The folder's name, relative to the page - which is how the page asks for
    // a shard, since the two are laid out side by side. DataFiles owns WHERE
    // the folder is (that is data's job, not report's); this is only what it is
    // called, and a test holds the two together.
    public static final String FOLDER = "ledger";

    public record Result(int players, long rows, int shards, String runId, long bytes) {
    }

    private LedgerWriter() {
    }

    public static Result write(Path results, Path snapshot, Path outDir)
        throws SQLException, IOException {

        List<StringBuilder> payloads = new ArrayList<>(SHARDS);
        List<Dictionary> clubs = new ArrayList<>(SHARDS);
        List<Dictionary> comps = new ArrayList<>(SHARDS);
        for (int i = 0; i < SHARDS; i++) {
            payloads.add(new StringBuilder());
            clubs.add(new Dictionary());
            comps.add(new Dictionary());
        }

        int players = 0;
        long rows = 0;
        String runId;

        try (ResultsFile file = ResultsFile.open(results, snapshot)) {
            runId = file.runId();
            build(file.statement());

            // Streamed in one pass, ordered so a player's rows arrive together
            // and in the order the chart samples them. Nothing in between needs
            // to understand a career, and 2.2M rows collected into objects
            // first would be the one part of this step that did.
            try (ResultSet rs = file.statement().executeQuery("""
                SELECT player_id, match_id, match_date, my_club, opp_club, competition,
                       gf, ga, minutes_before, minutes_played, goal_value,
                       expectation_drained, residual, rating_before, rating_after, level
                FROM ledger_rows
                ORDER BY player_id %% %d, player_id, match_date, match_id"""
                .formatted(SHARDS))) {

                Career career = null;
                while (rs.next()) {
                    long id = rs.getLong("player_id");
                    if (career == null || career.playerId != id) {
                        if (career != null) {
                            career.flushInto(payloads);
                            players++;
                        }
                        int shard = (int) Math.floorMod(id, SHARDS);
                        career = new Career(id, shard, clubs.get(shard), comps.get(shard));
                    }
                    career.add(rs);
                    rows++;
                }
                if (career != null) {
                    career.flushInto(payloads);
                    players++;
                }
            }
        }

        long bytes = 0;
        Files.createDirectories(outDir);
        for (int i = 0; i < SHARDS; i++) {
            bytes += writeShard(outDir, i, runId, clubs.get(i), comps.get(i), payloads.get(i));
        }
        return new Result(players, rows, SHARDS, runId, bytes);
    }

    // Everything the log says about a match that rating_history does not, joined
    // rather than looked up. #17 left the warning this obeys: PLAYER_FACTS_SQL
    // resolves names with correlated subqueries over game_lineups' 3.18M rows,
    // which is fine for one match at a time and "would not be if anything ever
    // derived matches in bulk". This is that bulk case.
    private static void build(Statement s) throws SQLException {
        // games.game_id is VARCHAR and game_lineups.game_id is INTEGER, exactly
        // as the vendor ships them. TRY_CAST, never CAST: a fixture whose id is
        // not a number is one this log has nothing to say about, and it must
        // not take the whole build down with it.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE fixture AS
              SELECT TRY_CAST(g.game_id AS BIGINT) AS match_id,
                     g.competition_id, g.home_club_id, g.away_club_id,
                     g.home_club_goals, g.away_club_goals
              FROM tm.games g
              WHERE TRY_CAST(g.game_id AS BIGINT) IS NOT NULL""");

        // his_club - which club he played for that day - is ResultsFile's, not
        // this writer's, since #23: the chart's Tenure bands are cut from the
        // same rows this log prints a club on, and two resolutions of "which
        // club" would put a transfer at one date under a chart showing it at
        // another.
        //
        // The result reads from HIS side, always: "3-0" is what he won by, not
        // what the fixture list says. No home/away marker is claimed - the
        // glossary's Home side entry is explicit that the vendor's label is
        // administrative and names nobody at a neutral host, so a marker read
        // off it would assert a venue fact the data does not carry. Nor is
        // games.aggregate used: it is the TIE's number over two legs and this
        // row is one leg.
        //
        // Every join here is LEFT. A match the snapshot cannot describe still
        // moved a rating, so it is still logged, with nothing claimed about who
        // it was against - an inner join would drop the row and the row-count
        // gate would then fail a long way from the cause.
        // His side is worked out once, in `side`, and the three things that
        // depend on it - the opponent and the two halves of the score - are
        // read off it. Written this way round so the CASE that decides which
        // side he was on appears exactly once: three copies of it is three
        // chances for one to be the wrong way round, and a flipped score is
        // invisible to everything downstream.
        s.execute("""
            CREATE OR REPLACE TEMP TABLE side AS
              SELECT hc.match_id, hc.player_id, hc.club_id,
                     CASE WHEN hc.club_id = f.home_club_id THEN f.away_club_id
                          WHEN hc.club_id = f.away_club_id THEN f.home_club_id END AS opp_id,
                     CASE WHEN hc.club_id = f.home_club_id THEN f.home_club_goals
                          WHEN hc.club_id = f.away_club_id THEN f.away_club_goals END AS gf,
                     CASE WHEN hc.club_id = f.home_club_id THEN f.away_club_goals
                          WHEN hc.club_id = f.away_club_id THEN f.home_club_goals END AS ga,
                     f.competition_id
              FROM his_club hc LEFT JOIN fixture f ON f.match_id = hc.match_id""");

        // The level a row reads - index(P - D(age that day)), rounded to the one
        // decimal a chart can show - computed HERE, by the same expression that
        // draws the chart point, and shipped rather than re-derived on the page.
        //
        // This is the one place the design note was overruled in the building,
        // and #46's rule is why. It says to store P alone and derive the level
        // on the page from the date of birth the page already holds - which is
        // a saving of about six bytes a row and a JAVASCRIPT COPY OF THE AGEING
        // CURVE, knot table and exact-age arithmetic included. That is precisely
        // the second copy ImpactIndex.sql and AgeingCurve.penaltySql exist to
        // prevent, and it would be a copy nothing in `mvn test` can reach. It
        // cannot even go wrong yet, because the curve is pinned flat - which is
        // exactly how it would ship undetected and then diverge silently the day
        // stage 2 fits it. So the log reads its level off the same expression as
        // the chart, and the two cannot disagree.
        //
        // P is shipped as well, and is not redundant: the residual arithmetic a
        // row unfolds is in Value units, and #41's peak line is index(P) with no
        // curve subtracted at all.
        String level = ImpactIndex.sql("(h.rating_after - (%s))".formatted(
            AgeingCurve.pinned(Map.of())
                .penaltySql(AgeingCurve.ageSql("b.dob", "h.match_date"))));
        s.execute("""
            CREATE OR REPLACE TEMP TABLE ledger_rows AS
              SELECT h.player_id, h.match_id, h.match_date,
                     mine.name AS my_club, theirs.name AS opp_club,
                     comp.name AS competition, sd.gf, sd.ga,
                     h.minutes_before, h.minutes_played,
                     h.goal_value, h.expectation_drained, h.residual,
                     h.rating_before, h.rating_after,
                     round(%s, 1) AS level
              FROM r.rating_history h
                   JOIN eligible_ids e ON e.player_id = h.player_id
                   LEFT JOIN born b ON b.player_id = h.player_id
                   LEFT JOIN side sd
                          ON sd.match_id = h.match_id AND sd.player_id = h.player_id
                   LEFT JOIN club_name mine ON mine.club_id = sd.club_id
                   LEFT JOIN club_name theirs ON theirs.club_id = sd.opp_id
                   LEFT JOIN tm.competitions comp
                          ON comp.competition_id = sd.competition_id"""
            // competitions.name is the vendor's SLUG - "eredivisie",
            // "knvb-beker" - and it is the only name the snapshot has; name and
            // competition_code are the same column twice. Shown verbatim rather
            // than title-cased into something prettier, because a prettier name
            // would be one this project invented, and the rest of the repo
            // (SidecarStore's other-games list) already shows the raw id.
            .formatted(level));
    }

    // One player's rows, accumulated columnar. Columnar because a career's
    // twelve fields repeat their key on every row otherwise, and 50 rows of
    // {"date":...,"club":...} is most of a shard spent on punctuation.
    private static final class Career {

        private final long playerId;
        private final int shard;
        private final Dictionary clubs;
        private final Dictionary comps;

        private double p0;
        private double firstBefore;
        private boolean seenFirst;

        private final StringBuilder d = new StringBuilder();
        private final StringBuilder g = new StringBuilder();
        private final StringBuilder c = new StringBuilder();
        private final StringBuilder o = new StringBuilder();
        private final StringBuilder k = new StringBuilder();
        private final StringBuilder gf = new StringBuilder();
        private final StringBuilder ga = new StringBuilder();
        private final StringBuilder m = new StringBuilder();
        private final StringBuilder gv = new StringBuilder();
        private final StringBuilder xd = new StringBuilder();
        private final StringBuilder r = new StringBuilder();
        private final StringBuilder v = new StringBuilder();
        private final StringBuilder l = new StringBuilder();

        Career(long playerId, int shard, Dictionary clubs, Dictionary comps) {
            this.playerId = playerId;
            this.shard = shard;
            this.clubs = clubs;
            this.comps = comps;
        }

        void add(ResultSet rs) throws SQLException {
            if (!seenFirst) {
                // The chain's only stored end. after(i) == before(i+1) holds
                // because a rating is frozen for a whole match and moves once,
                // at the whistle (the glossary's Rating period), so every other
                // "before" in the career is the previous row's "after" and
                // storing it would be storing it twice.
                firstBefore = rs.getDouble("minutes_before");
                p0 = rs.getDouble("rating_before");
                seenFirst = true;
            }
            comma();
            d.append(rs.getDate("match_date").toLocalDate().toEpochDay());
            g.append(rs.getLong("match_id"));
            c.append(clubs.index(rs.getString("my_club")));
            o.append(clubs.index(rs.getString("opp_club")));
            k.append(comps.index(rs.getString("competition")));
            gf.append(integer(rs, "gf"));
            ga.append(integer(rs, "ga"));
            m.append(number(rs.getDouble("minutes_played")));
            gv.append(number(rs.getDouble("goal_value")));
            xd.append(number(rs.getDouble("expectation_drained")));
            r.append(number(rs.getDouble("residual")));
            v.append(number(rs.getDouble("rating_after")));
            l.append(number(rs.getDouble("level")));
        }

        private void comma() {
            if (d.length() > 0) {
                for (StringBuilder b : List.of(d, g, c, o, k, gf, ga, m, gv, xd, r, v, l)) {
                    b.append(',');
                }
            }
        }

        void flushInto(List<StringBuilder> payloads) {
            StringBuilder out = payloads.get(shard);
            if (out.length() > 0) {
                out.append(',');
            }
            out.append('"').append(playerId).append("\":{")
                .append("\"b0\":").append(number(firstBefore))
                .append(",\"p0\":").append(number(p0))
                .append(",\"d\":[").append(d).append(']')
                .append(",\"g\":[").append(g).append(']')
                .append(",\"c\":[").append(c).append(']')
                .append(",\"o\":[").append(o).append(']')
                .append(",\"k\":[").append(k).append(']')
                .append(",\"gf\":[").append(gf).append(']')
                .append(",\"ga\":[").append(ga).append(']')
                .append(",\"m\":[").append(m).append(']')
                .append(",\"gv\":[").append(gv).append(']')
                .append(",\"xd\":[").append(xd).append(']')
                .append(",\"r\":[").append(r).append(']')
                .append(",\"v\":[").append(v).append(']')
                .append(",\"l\":[").append(l).append("]}");
        }
    }

    // Clubs and competitions repeat all over a career, so each is named once
    // per shard and referenced by index. PER SHARD, not once globally: a shard
    // is loaded alone and must be readable alone, and a global dictionary of
    // 2,867 clubs copied 256 times costs more than the repetition it saves.
    private static final class Dictionary {

        private final Map<String, Integer> byName = new LinkedHashMap<>();

        // null is a name nothing in the snapshot supplies, and it stays null
        // rather than becoming "unknown": the page says what is missing in
        // words, and a placeholder in the data would be indistinguishable from
        // a club really called that.
        String index(String name) {
            if (name == null) {
                return "null";
            }
            return String.valueOf(byName.computeIfAbsent(name, n -> byName.size()));
        }

        String json() {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (String name : byName.keySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(Json.escape(name)).append('"');
            }
            return out.append(']').toString();
        }
    }

    // Every shard is written, empty ones included, so a folder that is SHORT is
    // a folder the page can SEE is short - a half-copied ledger is one of the
    // three failures the guard has to name, and 256 files that are always there
    // is what makes it detectable. An empty shard still carries the run id, so
    // even a player with no log cannot be shown another run's.
    private static long writeShard(Path outDir, int shard, String runId,
                                   Dictionary clubs, Dictionary comps, StringBuilder players)
        throws IOException {

        String js = PAYLOAD_PREFIX + '"' + String.format(Locale.ROOT, "%03d", shard) + "\",{"
            + "\"run\":\"" + Json.escape(runId) + "\""
            + ",\"clubs\":" + clubs.json()
            + ",\"comps\":" + comps.json()
            + ",\"players\":{" + players + "}}"
            + PAYLOAD_SUFFIX + '\n';

        byte[] bytes = js.getBytes(StandardCharsets.UTF_8);
        AtomicWrite.toFile(outDir.resolve(String.format(Locale.ROOT, "%03d.js", shard)), bytes);
        return bytes.length;
    }

    // Full precision, deliberately. The three goals-unit columns are gated at
    // 1e-9 and the stored rating is rescaled to a chart point at one decimal,
    // so a rounded shard would fail its own gate - and shortening a number to
    // save bytes is exactly the kind of saving that turns into a drift nobody
    // can find. Double.toString is the shortest string that reads back as the
    // same double, so this is compact AND exact; only the trailing ".0" comes
    // off, which changes nothing about what parses back.
    private static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalStateException("the history holds a non-finite number: " + d);
        }
        String s = Double.toString(d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? "null" : String.valueOf(value);
    }

}
