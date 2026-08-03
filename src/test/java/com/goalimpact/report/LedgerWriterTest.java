package com.goalimpact.report;

import com.goalimpact.data.DataFiles;
import com.goalimpact.engine.AgeingCurve;
import com.goalimpact.engine.PlayerMatch;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #24 stage 2's gate, held as a test rather than run as a script: the four
// arithmetic properties are checked over every row the writer emits, on a
// fixture the real writers stood up, so a regression fails `mvn test` rather
// than waiting for someone to re-run a query over 2.2M rows.
//
// It needs no real database, for ViewerWriterTest's reason: the history is
// written by RatingHistoryWriter itself - so the columns are agreed by
// construction rather than by copy - and the vendor snapshot is stood up as the
// tables the query actually reads. The chart is built by ViewerWriter from the
// same fixture, which is what makes the third gate arm - every drawn point
// equals its row's level to 1 dp - a real cross-check between two writers
// rather than an assertion about one.
class LedgerWriterTest {

    // Player 1 walks past the 1,000-minute threshold mid-career, so his log
    // carries both the drawn stretch and the stretch before the chart starts.
    private static final int MATCHES = 20;
    private static final double VALUE_AT_END = 12.5;

    @TempDir
    private Path dir;

    private Path results;
    private Path snapshot;
    private Path ledger;

    @BeforeEach
    void writeTheFilesTheLedgerReads() throws SQLException {
        results = dir.resolve("results.duckdb");
        snapshot = dir.resolve("snapshot.duckdb");
        ledger = dir.resolve("ledger");
        writeHistory(results);
        writeCareers(results);
        writeSnapshot(snapshot);
    }

    // The whole reason the log is a folder (#24): the population's log is
    // 222 MB and one player's is 50 rows, so a shard is fetched by arithmetic
    // on the id rather than by an index the page would have to carry.
    @Test
    void shardsEachPlayerIntoTheFileHisIdModulo256Names() throws Exception {
        LedgerWriter.write(results, snapshot, ledger);

        assertTrue(Files.exists(ledger.resolve("001.js")), "player 1 -> 001.js");
        assertTrue(Files.exists(ledger.resolve("045.js")), "player 301 -> 045.js");
        assertNotNull(player(1), "player 1 is in his own shard");
        assertNotNull(player(301), "player 301 is in his own shard");
        assertNull(shard(1).getAsJsonObject("players").get("301"),
            "and in nobody else's");
    }

    // Every one of the 256 is written, empty or not, so a folder that is SHORT
    // is a folder the page can see is short. A missing file is the half-copied
    // case the guard has to name.
    @Test
    void writesAllTwoHundredAndFiftySixShardsSoAShortFolderIsVisible() throws Exception {
        LedgerWriter.Result result = LedgerWriter.write(results, snapshot, ledger);

        assertEquals(256, result.shards());
        try (var files = Files.list(ledger)) {
            assertEquals(256, files.filter(p -> p.toString().endsWith(".js")).count());
        }
    }

    // ---- gate arm 1: the halves add up ---------------------------------
    // An equality is wrong and that was measured (stage 1 record): the residual
    // is summed in event order with goals and drains interleaved, so re-adding
    // the halves re-associates a floating-point sum. 979,700 of the run's
    // 2,478,228 history rows differ, by at most 3.55e-15, which leaves this
    // tolerance six orders of magnitude of headroom. (That is the WHOLE
    // history; the log covers the eligible 2,185,765 of it, and the worst
    // difference over those rows is the same 3.55e-15.)
    @Test
    void everyRowsTwoHalvesAddUpToTheResidualThatMovedTheRating() throws Exception {
        LedgerWriter.write(results, snapshot, ledger);

        long checked = 0;
        for (long id : List.of(1L, 301L)) {
            JsonObject p = player(id);
            JsonArray gv = p.getAsJsonArray("gv");
            JsonArray xd = p.getAsJsonArray("xd");
            JsonArray r = p.getAsJsonArray("r");
            assertEquals(gv.size(), xd.size());
            assertEquals(gv.size(), r.size());
            for (int i = 0; i < gv.size(); i++) {
                double sum = gv.get(i).getAsDouble() + xd.get(i).getAsDouble();
                assertTrue(Math.abs(sum - r.get(i).getAsDouble()) < 1e-9,
                    "row " + i + " of player " + id + ": " + sum + " vs " + r.get(i));
                checked++;
            }
        }
        assertTrue(checked > 0, "the gate checked something");
    }

    // ---- gate arm 2: after(i) == before(i+1) ---------------------------
    // Which is why only ONE rating is stored per row. The chain is the storage
    // decision and the assertion at once: if a rating could move between
    // matches, the log would have to carry both ends of every row.
    @Test
    void storesOneRatingPerRowBecauseAfterIsTheNextBefore() throws Exception {
        LedgerWriter.write(results, snapshot, ledger);
        JsonObject p = player(1);
        JsonArray v = p.getAsJsonArray("v");

        assertNull(p.get("before"), "the log stores no second rating column");
        double before = p.get("p0").getAsDouble();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + results);
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT rating_before, rating_after FROM rating_history"
                 + " WHERE player_id = 1 ORDER BY match_date, match_id")) {
            int i = 0;
            while (rs.next()) {
                assertEquals(rs.getDouble("rating_before"), before, 0.0,
                    "row " + i + "'s before is the previous row's after");
                before = v.get(i).getAsDouble();
                assertEquals(rs.getDouble("rating_after"), before, 0.0);
                i++;
            }
            assertEquals(MATCHES, i);
        }
    }

    // ---- gate arm 3: every drawn point equals its row's level to 1 dp ---
    // The one contract #46 asked to be held in a test, now across two writers:
    // a chart point is a month's last rating and a log row is a match, so the
    // page and the log speak the same Impact index or the drill-down lies.
    // The log ships the level rather than re-deriving it in JavaScript, so this
    // arm is about the two AGGREGATIONS agreeing - a chart point is a month's
    // last rating and a log row is a match - and not about two copies of a
    // rescale. The shipped level is checked against the Java curve besides, so
    // that the SQL expression really is the one the engine charges.
    @Test
    void everyDrawnChartPointEqualsItsRowsLevelToOneDecimal() throws Exception {
        Path html = dir.resolve("viewer.html");
        ViewerWriter.write(results, snapshot, html);
        LedgerWriter.write(results, snapshot, ledger);

        JsonObject drawn = drawnPlayer(html, 1);
        JsonArray ms = drawn.getAsJsonArray("ms");
        JsonArray vs = drawn.getAsJsonArray("vs");
        JsonObject logged = player(1);
        JsonArray d = logged.getAsJsonArray("d");
        JsonArray v = logged.getAsJsonArray("v");
        JsonArray l = logged.getAsJsonArray("l");

        for (int j = 0; j < d.size(); j++) {
            LocalDate date = LocalDate.ofEpochDay(d.get(j).getAsLong());
            assertEquals(round1(ImpactIndex.of(v.get(j).getAsDouble()
                    - AgeingCurve.pinned(Map.of()).at(date).forPlayer(1))),
                l.get(j).getAsDouble(), 1e-9,
                "row " + j + " reads the curve the replay charges");
        }

        for (int i = 0; i < ms.size(); i++) {
            int month = ms.get(i).getAsInt();
            // The month's LAST row, which is what the chart sampled.
            Double level = null;
            for (int j = 0; j < d.size(); j++) {
                LocalDate date = LocalDate.ofEpochDay(d.get(j).getAsLong());
                if (date.getYear() * 12 + date.getMonthValue() - 1 == month) {
                    level = l.get(j).getAsDouble();
                }
            }
            assertNotNull(level, "month " + month + " is drawn, so it has rows");
            assertEquals(vs.get(i).getAsDouble(), level, 1e-9,
                "the point drawn for month " + month);
        }
    }

    // ---- gate arm 4: the row count is rating_history's ------------------
    // For the eligible population, which is ResultsFile's - minutes alone. A
    // name is what decides whether the page can DRAW a man, never whether the
    // model rated him, so the log is the wider of the two populations and the
    // nameless are logged and unclickable rather than missing.
    @Test
    void logsEveryRatedMatchOfEveryEligiblePlayerAndNobodyElse() throws Exception {
        LedgerWriter.Result result = LedgerWriter.write(results, snapshot, ledger);

        // Player 1 and player 301 clear 1,000 minutes; player 2 does not.
        assertEquals(2, result.players());
        assertEquals(2L * MATCHES, result.rows());
        assertEquals(MATCHES, player(1).getAsJsonArray("d").size(),
            "every rated match, not only the drawn ones");
        assertNull(shard(2).getAsJsonObject("players").get("2"),
            "under the threshold, so no log");
    }

    // The stretch before the chart starts is 18.1% of the log and it moved the
    // rating hardest - K is largest when exposure is low, and the line's first
    // drawn value is made entirely of it. A log that cannot account for its own
    // first point is not one, so the rows are listed; the page greys them and
    // says why, off the exposure the shard already carries.
    @Test
    void carriesTheExposureThatSaysWhichRowsTheChartNeverDrew() throws Exception {
        LedgerWriter.write(results, snapshot, ledger);
        JsonObject p = player(1);
        JsonArray m = p.getAsJsonArray("m");

        assertEquals(0.0, p.get("b0").getAsDouble(), 0.0,
            "his first rated match is his first exposure");
        double cum = p.get("b0").getAsDouble();
        int beforeTheChart = 0;
        for (int i = 0; i < m.size(); i++) {
            cum += m.get(i).getAsDouble();
            if (cum < ImpactIndex.ELIGIBLE_MINUTES) {
                beforeTheChart++;
            }
        }
        assertTrue(beforeTheChart > 0, "this career really does start under the threshold");
        assertEquals(MATCHES - beforeTheChart, drawnRowCount(),
            "the rows the chart drew are exactly the rest");
    }

    // A wrong-run log is refused rather than shown (#24): the shard says which
    // run it came from and the page holds it against the chart's.
    @Test
    void everyShardNamesTheRunItCameFromSoAWrongRunLogCanBeRefused() throws Exception {
        LedgerWriter.Result result = LedgerWriter.write(results, snapshot, ledger);

        assertEquals("TEST-RUN-k1.00", result.runId());
        assertEquals("TEST-RUN-k1.00", shard(1).get("run").getAsString());
        assertEquals("TEST-RUN-k1.00", shard(7).get("run").getAsString(),
            "including a shard with nobody in it");
    }

    // The result reads from HIS side, always. The vendor's home label is
    // administrative and names nobody at a neutral host (the glossary's Home
    // side), so no venue is claimed - only who he played and what it finished.
    @Test
    void orientsEveryResultFromHisOwnSideOfTheMatch() throws Exception {
        LedgerWriter.write(results, snapshot, ledger);
        JsonObject home = player(1);
        JsonObject away = player(301);
        JsonArray clubs = shard(1).getAsJsonArray("clubs");

        // Match 100: Cagliari 3-0 Internazionale. Player 1 is Cagliari's.
        assertEquals("Cagliari", clubs.get(home.getAsJsonArray("c").get(0).getAsInt()).getAsString());
        assertEquals("Internazionale",
            clubs.get(home.getAsJsonArray("o").get(0).getAsInt()).getAsString());
        assertEquals(3, home.getAsJsonArray("gf").get(0).getAsInt());
        assertEquals(0, home.getAsJsonArray("ga").get(0).getAsInt());

        // The same match, read from Internazionale's side: the score flips.
        JsonArray awayClubs = shard(301).getAsJsonArray("clubs");
        assertEquals("Internazionale",
            awayClubs.get(away.getAsJsonArray("c").get(0).getAsInt()).getAsString());
        assertEquals(0, away.getAsJsonArray("gf").get(0).getAsInt());
        assertEquals(3, away.getAsJsonArray("ga").get(0).getAsInt());
    }

    // Clubs and competitions repeat over a career, so they are named once per
    // shard and referenced by index. The dictionary is per shard because a
    // shard is loaded alone and has to be readable alone.
    @Test
    void namesClubsAndCompetitionsOncePerShard() throws Exception {
        LedgerWriter.write(results, snapshot, ledger);

        assertEquals(List.of("Serie A"), strings(shard(1).getAsJsonArray("comps")));
        assertEquals(0, player(1).getAsJsonArray("k").get(0).getAsInt());
        assertTrue(shard(1).getAsJsonArray("clubs").size() <= 2,
            "only the clubs this shard's players actually played for or against");
    }

    // A match the snapshot has no row for still moved a rating, so it is still
    // logged - with nothing claimed about who it was against. The join is a
    // LEFT join for exactly this, and a silently dropped row would make the
    // count gate fail somewhere far away from the cause.
    @Test
    void logsAMatchTheSnapshotCannotDescribeRatherThanDroppingIt() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM games WHERE game_id = '105'");
        }

        LedgerWriter.Result result = LedgerWriter.write(results, snapshot, ledger);

        assertEquals(2L * MATCHES, result.rows(), "no row is lost to a missing fixture");
        JsonObject p = player(1);
        assertTrue(p.getAsJsonArray("o").get(5).isJsonNull(), "no opponent claimed");
        assertTrue(p.getAsJsonArray("gf").get(5).isJsonNull(), "no score claimed");
        assertFalse(p.getAsJsonArray("r").get(5).isJsonNull(), "the residual is still there");
    }

    // The folder is rebuilt whole like the page and the results file it comes
    // from. A shard left behind by an earlier, wider run would be a log from
    // that run under this run's chart - the exact thing the run id refuses,
    // arriving through the back door.
    @Test
    void rewritesEveryShardSoNothingSurvivesFromAnEarlierRun() throws Exception {
        Files.createDirectories(ledger);
        Files.writeString(ledger.resolve("001.js"), "GI_LEDGER(\"001\",{\"run\":\"OLD\"});");

        LedgerWriter.write(results, snapshot, ledger);

        assertEquals("TEST-RUN-k1.00", shard(1).get("run").getAsString());
    }

    // Written atomically for the page's reason: a half-written shard parses as
    // a syntax error rather than as a short log, but a stray .tmp beside 256
    // real files is exactly the confusion temp-then-rename exists to avoid.
    @Test
    void leavesNoTemporaryFileBehind() throws Exception {
        LedgerWriter.write(results, snapshot, ledger);
        try (var entries = Files.list(ledger)) {
            List<String> stray = entries.map(p -> p.getFileName().toString())
                .filter(n -> !n.endsWith(".js")).toList();
            assertTrue(stray.isEmpty(), "left behind " + stray);
        }
    }

    // The same three refusals the page makes, because they are one guard in one
    // place now (ResultsFile): a log drawn over another run's Goalkeepers is
    // wrong the same way a chart is.
    @Test
    void refusesAResultsFileThatDoesNotSayWhoIsAGoalkeeper() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + results);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE player_careers");
        }

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> LedgerWriter.write(results, snapshot, ledger));
        assertTrue(e.getMessage().contains("player_careers"), e.getMessage());
    }

    // data owns WHERE the folder is and report owns what it is CALLED, and the
    // page asks for a shard by the second while the build step writes it at the
    // first. They agree here or the page looks in a folder nothing was written
    // to - which fails as "no match log" long after the cause.
    @Test
    void theFolderTheBuildWritesIsTheFolderThePageAsksFor() {
        assertEquals(LedgerWriter.FOLDER, DataFiles.LEDGER.getFileName().toString());
        assertEquals(DataFiles.VIEWER.toAbsolutePath().getParent(),
            DataFiles.LEDGER.toAbsolutePath().getParent(),
            "the page loads its shards by a relative path, so they are siblings");
    }

    // ---- fixtures -------------------------------------------------------

    private JsonObject shard(long playerId) throws IOException {
        return shardFile(ledger.resolve(String.format("%03d.js", playerId % 256)));
    }

    private JsonObject player(long playerId) throws IOException {
        return shard(playerId).getAsJsonObject("players")
            .getAsJsonObject(String.valueOf(playerId));
    }

    // Read back through the writer's own two markers rather than by guessing at
    // the JavaScript, the way ViewerWriterTest reads the page.
    private static JsonObject shardFile(Path file) throws IOException {
        String js = Files.readString(file, StandardCharsets.UTF_8);
        int call = js.indexOf(LedgerWriter.PAYLOAD_PREFIX);
        assertTrue(call >= 0, "the shard calls the page back: " + file);
        // The call's first argument is the shard's own name, so the payload
        // starts at the object after it.
        int from = js.indexOf('{', call + LedgerWriter.PAYLOAD_PREFIX.length());
        int to = js.lastIndexOf(LedgerWriter.PAYLOAD_SUFFIX);
        assertTrue(to > from, "the call is closed");
        return JsonParser.parseString(js.substring(from, to)).getAsJsonObject();
    }

    private static JsonObject drawnPlayer(Path html, long id) throws IOException {
        String page = Files.readString(html, StandardCharsets.UTF_8);
        int from = page.indexOf(ViewerWriter.DATA_PREFIX) + ViewerWriter.DATA_PREFIX.length();
        int to = page.indexOf(ViewerWriter.DATA_SUFFIX, from);
        for (JsonElement e : JsonParser.parseString(page.substring(from, to)).getAsJsonArray()) {
            if (e.getAsJsonObject().get("id").getAsLong() == id) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("player " + id + " is not drawn");
    }

    // How many of player 1's rated matches the chart actually drew, counted
    // from the history rather than from the ledger, so the two never agree by
    // sharing a bug.
    private int drawnRowCount() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + results);
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT count(*) AS n FROM (SELECT sum(minutes_played) OVER ("
                 + "  ORDER BY match_date, match_id) AS cum FROM rating_history"
                 + "  WHERE player_id = 1) WHERE cum >= " + ImpactIndex.ELIGIBLE_MINUTES)) {
            rs.next();
            return rs.getInt("n");
        }
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static double round1(double d) {
        return Math.round(d * 10) / 10.0;
    }

    // Two eligible players on opposite sides of the same twenty matches, plus
    // one who never reaches the threshold. 90 minutes each, so player 1 crosses
    // 1,000 career minutes during match 12 and the first eleven rows are the
    // stretch the chart never draws.
    private static void writeHistory(Path results) throws SQLException {
        try (RatingHistoryWriter history = new RatingHistoryWriter(results, "TEST-RUN-k1.00")) {
            double before = 0, awayBefore = 0;
            for (int i = 0; i < MATCHES; i++) {
                LocalDate date = LocalDate.of(2023, 4, 1).plusMonths(i);
                history.startMatch(100 + i, date);
                double after = VALUE_AT_END * (i + 1) / MATCHES;
                // The halves are given values that really add up, so a fixture
                // drifting from the arithmetic the gate checks fails here first.
                double goals = 1.0, drained = (after - before) - 1.0;
                history.playerMatch(new PlayerMatch(
                    1, i * 90.0, before, goals, drained, goals + drained, 90.0, after));
                before = after;

                double awayAfter = -0.4 * (i + 1) / MATCHES;
                double awayGoals = -1.0, awayDrained = (awayAfter - awayBefore) + 1.0;
                history.playerMatch(new PlayerMatch(301, i * 90.0, awayBefore,
                    awayGoals, awayDrained, awayGoals + awayDrained, 90.0, awayAfter));
                awayBefore = awayAfter;

                if (i < 5) {
                    history.playerMatch(
                        new PlayerMatch(2, i * 90.0, 0.2, 1.0, -1.0, 0.0, 90.0, 0.3));
                }
            }
        }
    }

    private static void writeCareers(Path results) throws SQLException {
        List<PlayerTally> careers = new ArrayList<>();
        for (long id : List.of(1L, 2L, 301L)) {
            careers.add(new PlayerTally(new Player(id, "Player " + id), new Team(7, "Cagliari")));
        }
        PlayerCareerWriter.write(results, "TEST-RUN-k1.00", careers);
    }

    // game_id is VARCHAR in games and INTEGER in game_lineups, exactly as the
    // vendor ships them - so a cast the writer forgot fails here.
    private static void writeSnapshot(Path snapshot) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE clubs (club_id BIGINT, name VARCHAR)");
            s.execute("INSERT INTO clubs VALUES (7, 'Cagliari'), (8, 'Internazionale')");
            s.execute("CREATE TABLE competitions (competition_id VARCHAR, name VARCHAR)");
            s.execute("INSERT INTO competitions VALUES ('IT1', 'Serie A')");
            s.execute("CREATE TABLE games (game_id VARCHAR, competition_id VARCHAR, date DATE,"
                + " home_club_id INTEGER, away_club_id INTEGER,"
                + " home_club_goals INTEGER, away_club_goals INTEGER)");
            s.execute("CREATE TABLE game_lineups (game_lineups_id VARCHAR, date DATE,"
                + " game_id INTEGER, player_id INTEGER, club_id INTEGER, player_name VARCHAR)");
            s.execute("CREATE TABLE players ("
                + "player_id BIGINT, position VARCHAR, date_of_birth TIMESTAMP)");
            s.execute("INSERT INTO players VALUES (2, 'Defender', TIMESTAMP '1999-01-05')");
            for (int i = 0; i < MATCHES; i++) {
                long game = 100 + i;
                LocalDate date = LocalDate.of(2023, 4, 1).plusMonths(i);
                s.execute("INSERT INTO games VALUES ('" + game + "', 'IT1', DATE '" + date
                    + "', 7, 8, 3, 0)");
                s.execute("INSERT INTO game_lineups VALUES ('g" + game + "a', DATE '" + date
                    + "', " + game + ", 1, 7, 'Nicolò Barella')");
                s.execute("INSERT INTO game_lineups VALUES ('g" + game + "b', DATE '" + date
                    + "', " + game + ", 301, 8, 'An Opponent')");
            }
        }
    }
}
