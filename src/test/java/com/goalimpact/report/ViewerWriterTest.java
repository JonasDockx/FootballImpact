package com.goalimpact.report;

import com.goalimpact.engine.AgeingCurve;
import com.goalimpact.engine.PlayerMatch;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The one contract #46 asked to be held in a test, and the reason the viewer is
// Java rather than the prototype's SQL plus shell glue: the column names, the
// 1,000-minute threshold and the Impact index rescale are shared between
// RatingHistoryWriter and the query that reads it back, and in the prototype the
// rescale was duplicated in idx.sql where nothing checked it.
//
// It needs no real database: the history is written by RatingHistoryWriter
// itself - so the columns are agreed by construction rather than by copy - and
// the vendor snapshot is stood up as the three tables the query actually reads.
class ViewerWriterTest {

    // Ratings chosen so the rescale is visible: the same Value must come out of
    // the page as ImpactIndex.of() produces here.
    private static final double VALUE_AT_END = 12.5;

    @TempDir
    private Path dir;

    private Path results;
    private Path snapshot;
    private Path page;

    @BeforeEach
    void writeTheFilesTheViewerReads() throws SQLException {
        results = dir.resolve("results.duckdb");
        snapshot = dir.resolve("snapshot.duckdb");
        page = dir.resolve("viewer.html");
        writeHistory(results);
        writeCareers(results, true);
        writeSnapshot(snapshot);
    }

    @Test
    void writesEveryEligiblePlayerAndNobodyElse() throws Exception {
        ViewerWriter.Result result = ViewerWriter.write(results, snapshot, page);

        // 1 is past the threshold and named; 2 is under it; 3 is past it but
        // named nowhere at all (the 619 of #35's measurement).
        assertEquals(1, result.players());
        JsonArray data = data(page);
        assertEquals(1, data.size());
        assertEquals(1L, data.get(0).getAsJsonObject().get("id").getAsLong());
    }

    // ADR 0016: rating_history stores P, the estimated peak, so the drawn line
    // is P - D(age that day) rescaled - and D comes from AgeingCurve, never from
    // a second knot table written into the query. Player 1 has no date of birth,
    // so he is charged the unknown-date constant, exactly as the replay charges
    // him. Both sides are zero while stage 1's table is flat, which is what
    // makes the page byte-identical across the change; the assertion is written
    // through the curve so that stops being true the day the curve is fitted.
    @Test
    void rescalesWithTheSameConstantsAsJavaAndSubtractsTheSameCurve() throws Exception {
        ViewerWriter.write(results, snapshot, page);

        double penalty = AgeingCurve.pinned(Map.of())
            .at(LocalDate.of(2024, 5, 1)).forPlayer(1);
        double drawn = ImpactIndex.of(VALUE_AT_END - penalty);
        JsonObject player = data(page).get(0).getAsJsonObject();
        JsonArray series = player.getAsJsonArray("vs");
        double last = series.get(series.size() - 1).getAsDouble();
        // The page rounds to one decimal, which is all a chart can show.
        assertEquals(round1(drawn), last, 1e-9);
        assertEquals(round1(drawn), player.get("latest").getAsDouble(), 1e-9);
    }

    // #22, ADR 0016: with two ageing curves the page has to know which one a
    // player is drawn against, and the run is the only thing that knows it
    // authoritatively - a man the run never saw start in goal is a field player
    // to the model that rated him, whatever the vendor's lineups say.
    @Test
    void carriesTheRunsCareerTagRatherThanTheVendorsPosition() throws Exception {
        ViewerWriter.write(results, snapshot, page);

        assertTrue(data(page).get(0).getAsJsonObject().get("goalkeeper").getAsBoolean());

        writeCareers(results, false);
        ViewerWriter.write(results, snapshot, page);

        assertFalse(data(page).get(0).getAsJsonObject().get("goalkeeper").getAsBoolean());
    }

    // A results file with no tags cannot be drawn honestly, so it is not drawn:
    // a page that quietly made everyone a field player would look exactly like a
    // correct one.
    @Test
    void refusesAResultsFileThatDoesNotSayWhoIsAGoalkeeper() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + results);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE player_careers");
        }

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ViewerWriter.write(results, snapshot, page));
        assertTrue(e.getMessage().contains("player_careers"), e.getMessage());
    }

    // A table that is merely SHORT is the same silence with a table present:
    // the join defaults a missing row to a field player.
    @Test
    void refusesAResultsFileThatTagsOnlySomeOfThePopulation() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + results);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM player_careers WHERE player_id = 1");
        }

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ViewerWriter.write(results, snapshot, page));
        assertTrue(e.getMessage().contains("short"), e.getMessage());
    }

    // And half of one run beside half of another is worse than either alone.
    @Test
    void refusesTagsFromADifferentRun() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + results);
             Statement s = c.createStatement()) {
            s.execute("UPDATE player_careers SET run_id = 'ANOTHER-RUN'");
        }

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> ViewerWriter.write(results, snapshot, page));
        assertTrue(e.getMessage().contains("ANOTHER-RUN"), e.getMessage());
    }

    // #35: names come from game_lineups, never from players - 94,902 of 95,521
    // rated players are named there against 40,364 with a players row. A player
    // with no players row still gets a line; he only loses his age axis.
    @Test
    void namesComeFromLineupsAndAMissingPlayersRowIsNotFatal() throws Exception {
        ViewerWriter.write(results, snapshot, page);

        JsonObject player = data(page).get(0).getAsJsonObject();
        assertEquals("Nicolò Barella", player.get("name").getAsString());
        assertEquals("Internazionale", player.get("club").getAsString());
        assertTrue(player.get("dobm").isJsonNull(), "no players row, so no age axis");
    }

    // A players row is an identity card, not a name source - guarding the join
    // direction, which is the easiest thing here to get backwards.
    @Test
    void aPlayersRowIsNotANameSource() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO players VALUES (3, 'Attack', TIMESTAMP '1995-02-02')");
        }

        ViewerWriter.Result result = ViewerWriter.write(results, snapshot, page);

        assertEquals(1, result.players());
        for (var element : data(page)) {
            JsonObject drawn = element.getAsJsonObject();
            assertNotNull(drawn.get("name"));
            assertEquals(1L, drawn.get("id").getAsLong(),
                "player 3 is named nowhere a lineup can see");
        }
    }

    // #46: the page states the last match date and the run id, and deliberately
    // not the build time - a build clock reads 'today' over a March history.
    @Test
    void statesTheRunItWasBuiltFrom() throws Exception {
        ViewerWriter.Result result = ViewerWriter.write(results, snapshot, page);
        String html = Files.readString(page, StandardCharsets.UTF_8);

        assertEquals("TEST-RUN-k1.00", result.runId());
        assertEquals(LocalDate.of(2024, 5, 1), result.lastMatchDate());
        assertTrue(html.contains("TEST-RUN-k1.00"), "run id on the page");
        assertTrue(html.contains("2024-05-01"), "last match date on the page");
    }

    // Written atomically (#46): a half-written HTML page still renders, just
    // wrongly. Nothing may be left behind beside it either.
    @Test
    void leavesNoTemporaryFileBehind() throws Exception {
        ViewerWriter.write(results, snapshot, page);
        try (var entries = Files.list(dir)) {
            List<String> stray = entries.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".tmp") || n.endsWith(".json"))
                .toList();
            assertTrue(stray.isEmpty(), "left behind " + stray);
        }
    }

    // The page carries the pinned constants it draws with, so a change to
    // ImpactIndex or RankLadder reaches the chart without a second edit.
    @Test
    void carriesThePinnedConstants() throws Exception {
        ViewerWriter.write(results, snapshot, page);
        String html = Files.readString(page, StandardCharsets.UTF_8);

        assertTrue(html.contains("177.2"), "the Top 20 tick");
        assertTrue(html.contains("Top 2000"), "the ladder's last rung");
        assertTrue(html.contains(String.valueOf(RankLadder.POOL)), "the pool the ladder read");
        assertTrue(html.contains("\"CENTRE\":100"), "the population average the chart marks");
        assertFalse(html.contains("{{"), "every placeholder filled");
        // Substitution replaces every occurrence, so a placeholder mentioned
        // anywhere else in the template - a comment documenting it, say - would
        // silently double the population the page carries.
        assertEquals(1, count(html, ViewerWriter.DATA_PREFIX), "one data block");
        assertEquals(1, count(html, ViewerWriter.DATA_SUFFIX), "one data block");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static double round1(double d) {
        return Math.round(d * 10) / 10.0;
    }

    private static JsonArray data(Path page) throws Exception {
        String html = Files.readString(page, StandardCharsets.UTF_8);
        int from = html.indexOf(ViewerWriter.DATA_PREFIX);
        assertTrue(from >= 0, "the page carries its data");
        from += ViewerWriter.DATA_PREFIX.length();
        int to = html.indexOf(ViewerWriter.DATA_SUFFIX, from);
        assertTrue(to > from, "the data block is closed");
        return JsonParser.parseString(html.substring(from, to)).getAsJsonArray();
    }

    // Three players, one of each kind the query has to sort out. Player 1 walks
    // past 1,000 minutes and ends at VALUE_AT_END; player 2 stops short of the
    // threshold; player 3 is past it but appears in no lineup, so nothing names
    // him and he cannot be drawn.
    private static void writeHistory(Path results) throws SQLException {
        try (RatingHistoryWriter history = new RatingHistoryWriter(results, "TEST-RUN-k1.00")) {
            List<LocalDate> dates = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                dates.add(LocalDate.of(2023, 4, 1).plusMonths(i));
            }
            for (int i = 0; i < dates.size(); i++) {
                LocalDate date = dates.get(i);
                history.startMatch(100 + i, date);
                double before = i * 90.0;
                double rating = VALUE_AT_END * (i + 1) / dates.size();
                // #24: the residual arrives as its two halves and its sum. The
                // halves are given values that really add up, so a fixture that
                // drifts from the arithmetic the page checks fails here first.
                history.playerMatch(new PlayerMatch(1, before, rating - 0.5, 1.0, -0.9, 0.1, 90.0, rating));
                if (i < 5) {
                    history.playerMatch(new PlayerMatch(2, before, 0.2, 1.0, -1.0, 0.0, 90.0, 0.3));
                }
                history.playerMatch(new PlayerMatch(3, before, 1.0, 0.0, 0.0, 0.0, 90.0, 1.1));
            }
        }
    }

    // The career tags of the same run: written by the writer the replay uses,
    // so the table the page reads is agreed by construction rather than by copy
    // - the same argument that has the history above written by its own writer.
    private static void writeCareers(Path results, boolean oneKeptGoal) throws SQLException {
        List<PlayerTally> careers = new ArrayList<>();
        for (long id = 1; id <= 3; id++) {
            PlayerTally tally = new PlayerTally(new Player(id, "Player " + id), new Team(7, "Cagliari"));
            if (id == 1 && oneKeptGoal) {
                tally.startedInGoal();
            }
            careers.add(tally);
        }
        PlayerCareerWriter.write(results, "TEST-RUN-k1.00", careers);
    }

    private static void writeSnapshot(Path snapshot) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE clubs (club_id BIGINT, name VARCHAR)");
            s.execute("INSERT INTO clubs VALUES (7, 'Cagliari'), (8, 'Internazionale')");
            s.execute("CREATE TABLE game_lineups ("
                + "player_id BIGINT, club_id BIGINT, date DATE, player_name VARCHAR)");
            s.execute("INSERT INTO game_lineups VALUES "
                + "(1, 7, DATE '2023-04-01', 'Nicolò Barella'), "
                + "(1, 8, DATE '2024-05-20', 'Nicolò Barella'), "
                + "(2, 7, DATE '2023-04-01', 'A Reserve')");
            s.execute("CREATE TABLE players ("
                + "player_id BIGINT, position VARCHAR, date_of_birth TIMESTAMP)");
            s.execute("INSERT INTO players VALUES (2, 'Defender', TIMESTAMP '1999-01-05')");
        }
    }
}
