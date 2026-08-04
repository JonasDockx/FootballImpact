package com.goalimpact.report;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // #23: where he was playing, on the same month axis the line is drawn on.
    // Two bands here, and both of the rules that put them there: the first is
    // CLIPPED to where the chart starts (his Cagliari run opened eleven months
    // before the 1,000th minute), and the second opens at his first recorded
    // match for the new club.
    @Test
    void cutsTheCareerIntoClubBandsOnTheChartsOwnMonthAxis() throws Exception {
        ViewerWriter.Result result = ViewerWriter.write(results, snapshot, page);

        JsonObject player = data(page).get(0).getAsJsonObject();
        JsonArray sm = player.getAsJsonArray("sm");
        JsonArray sc = player.getAsJsonArray("sc");
        JsonArray clubs = clubs(page);

        assertEquals(2, sm.size(), "one band a club, in career order");
        assertEquals(2, result.bands());
        assertEquals(2024 * 12 + 2, sm.get(0).getAsInt(), "clipped to the first drawn month");
        assertEquals(2024 * 12 + 3, sm.get(1).getAsInt(), "his first match for the new club");
        assertEquals("Cagliari", clubs.get(sc.get(0).getAsInt()).getAsString());
        assertEquals("Internazionale", clubs.get(sc.get(1).getAsInt()).getAsString());
    }

    // A cap is not a move: the country is not a club, and a band cut at every
    // international window would say a player left his club nine times a season.
    // He is capped in the middle of the Cagliari run here, and the run stays one
    // band - which is only visible because the band before the cap and the band
    // after it would otherwise both be Cagliari.
    //
    // The competition is what excludes it, and it has to be: the fixture list
    // names a national side as readily as a club, so 'Italy' is a perfectly
    // available name here and is still not a band.
    @Test
    void aNationalTeamMatchOpensNoBandAndDoesNotCutTheRunAroundIt() throws Exception {
        ViewerWriter.write(results, snapshot, page);

        JsonObject player = data(page).get(0).getAsJsonObject();
        assertEquals(2, player.getAsJsonArray("sm").size(), "the cap cuts nothing");
        for (JsonElement name : clubs(page)) {
            assertNotEquals("Italy", name.getAsString(), "a country is never a band");
        }
        assertEquals("Internazionale", player.get("club").getAsString(),
            "nor the club on his id card");
    }

    // A club is named from the fixture list where it has no squad page, which is
    // most of them: tm.clubs holds 796 rows against the 3,144 clubs that appear
    // in lineups, and reading it alone left 3,515 of 25,970 drawn careers with no
    // club at all. Where both name a club the squad page wins - the fixture list
    // abbreviates.
    @Test
    void namesAClubFromTheFixtureListWhereItHasNoSquadPage() throws Exception {
        ViewerWriter.write(results, snapshot, page);
        JsonArray sc = data(page).get(0).getAsJsonObject().getAsJsonArray("sc");
        assertEquals("Cagliari", clubs(page).get(sc.get(0).getAsInt()).getAsString(),
            "the squad page's name, not the fixture list's 'Cagliari Calcio'");

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM clubs WHERE club_id = 8");
        }

        ViewerWriter.write(results, snapshot, page);

        JsonObject player = data(page).get(0).getAsJsonObject();
        assertEquals(2, player.getAsJsonArray("sm").size(), "still banded");
        assertEquals("Inter Milan", clubs(page)
            .get(player.getAsJsonArray("sc").get(1).getAsInt()).getAsString());
    }

    // A player nothing in the snapshot places gets a line and no bands, rather
    // than a band claiming a club he may never have played for. His name comes
    // from a lineup, so one is left behind for the cap: he is drawn, he is
    // named, and the only club anything says he played for is a country.
    @Test
    void aCareerWithNoClubOnRecordIsDrawnWithNoBands() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM game_lineups WHERE player_id = 1 AND club_id IN (7, 8)");
        }

        ViewerWriter.Result result = ViewerWriter.write(results, snapshot, page);

        assertEquals(1, result.players(), "he is still drawn");
        assertEquals(0, result.bands());
        assertEquals(0, data(page).get(0).getAsJsonObject().getAsJsonArray("sm").size());
        assertEquals(0, clubs(page).size());
    }

    // 31 of the run's club names carry an apostrophe or an ampersand - Connah's
    // Quay Nomads, Brighton & Hove Albion - and the chart serialises its geometry
    // into a single-quoted HTML attribute the hover handler parses back. One
    // unescaped apostrophe truncates that attribute and takes the crosshair down
    // with it, so the page has to hand the name over intact and escape it where
    // it is written, never the other way round.
    @Test
    void carriesAClubNameWithAnApostropheThroughToThePage() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("UPDATE clubs SET name = 'Connah''s Quay & Shotton' WHERE club_id = 8");
        }

        ViewerWriter.write(results, snapshot, page);

        JsonObject player = data(page).get(0).getAsJsonObject();
        int last = player.getAsJsonArray("sc").get(1).getAsInt();
        assertEquals("Connah's Quay & Shotton", clubs(page).get(last).getAsString(),
            "the name reaches the page exactly as the snapshot spells it");
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

    // #48's boundary reaches the page from Java, on the axis the chart draws
    // careers on. An absolute month is easy to hand over in the wrong units -
    // a year, a month-of-year, an epoch offset - and every one of them would
    // put the vertical line somewhere plausible and wrong.
    @Test
    void carriesTheBurnInBoundaryOnTheSameMonthAxisAsTheCareers() throws Exception {
        ViewerWriter.write(results, snapshot, page);
        String html = Files.readString(page, StandardCharsets.UTF_8);

        assertTrue(html.contains("\"BURNIN_BOUNDARY\":" + BurnIn.BOUNDARY_MONTH),
            "the boundary, as an absolute month");
        assertTrue(html.contains("\"BURNIN_FROM\":" + BurnIn.FROM_MONTH), "where the ramp starts");
        assertTrue(html.contains("\"BURNIN_FLOOR\":"), "the legibility floor");

        // The fixture's career is sampled onto that same axis, so the two are
        // comparable numbers rather than two conventions that happen to agree.
        // It starts in March 2024 rather than at his first match, because that
        // is where his twelfth 90 minutes carry him past the threshold.
        JsonArray ms = data(page).get(0).getAsJsonObject().getAsJsonArray("ms");
        assertEquals(2024 * 12 + 2, ms.get(0).getAsInt(), "on the page's own month axis");
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

    // #23's band dictionary, read back through the writer's own two markers for
    // the reason the data block is.
    private static JsonArray clubs(Path page) throws Exception {
        return block(page, ViewerWriter.CLUBS_PREFIX, ViewerWriter.CLUBS_SUFFIX);
    }

    private static JsonArray data(Path page) throws Exception {
        return block(page, ViewerWriter.DATA_PREFIX, ViewerWriter.DATA_SUFFIX);
    }

    private static JsonArray block(Path page, String prefix, String suffix) throws Exception {
        String html = Files.readString(page, StandardCharsets.UTF_8);
        int from = html.indexOf(prefix);
        assertTrue(from >= 0, "the page carries " + prefix);
        from += prefix.length();
        int to = html.indexOf(suffix, from);
        assertTrue(to > from, prefix + " is closed");
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

    // Player 1's fourteen matches, 100..113, one a month from April 2023. He
    // plays twelve for Cagliari, is capped by his country in the seventh - which
    // must open no band and must not cut the Cagliari run in two (#23) - and
    // moves to Internazionale for the thirteenth. The fourteenth is in no lineup
    // at all, so the band before it has to run on.
    private static void writeSnapshot(Path snapshot) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + snapshot);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE clubs (club_id BIGINT, name VARCHAR)");
            s.execute("INSERT INTO clubs VALUES (7, 'Cagliari'), (8, 'Internazionale')");
            s.execute("CREATE TABLE competitions (competition_id VARCHAR, name VARCHAR,"
                + " type VARCHAR)");
            s.execute("INSERT INTO competitions VALUES ('IT1', 'Serie A', 'domestic_league'),"
                + " ('EURO', 'uefa-euro', 'national_team_competition')");
            // home_club_name / away_club_name are the second name source, and
            // the wide one: clubs holds current squads only (796 rows against
            // 3,144 clubs in lineups), where a fixture names whoever played it.
            // Match 100 is against a club with no squad row, so the fixture list
            // is the only thing that can name it.
            s.execute("CREATE TABLE games (game_id VARCHAR, competition_id VARCHAR,"
                + " home_club_id INTEGER, away_club_id INTEGER,"
                + " home_club_name VARCHAR, away_club_name VARCHAR)");
            s.execute("CREATE TABLE game_lineups (game_id INTEGER, "
                + "player_id BIGINT, club_id BIGINT, date DATE, player_name VARCHAR)");
            for (int i = 0; i < 14; i++) {
                long game = 100 + i;
                LocalDate date = LocalDate.of(2023, 4, 1).plusMonths(i);
                // The seventh is the cap; 9 is Italy, which is in no lineup's
                // clubs table, exactly as the vendor ships national sides.
                long club = i == 6 ? 9 : i < 12 ? 7 : 8;
                s.execute("INSERT INTO games VALUES ('" + game + "', '"
                    + (i == 6 ? "EURO" : "IT1") + "', " + club + ", 11, '"
                    + (i == 6 ? "Italy" : i < 12 ? "Cagliari Calcio" : "Inter Milan")
                    + "', 'Reggiana 1919')");
                if (i == 13) {
                    continue;
                }
                s.execute("INSERT INTO game_lineups VALUES (" + game + ", 1, " + club
                    + ", DATE '" + date + "', 'Nicolò Barella')");
            }
            s.execute("INSERT INTO game_lineups VALUES "
                + "(100, 2, 7, DATE '2023-04-01', 'A Reserve')");
            s.execute("CREATE TABLE players ("
                + "player_id BIGINT, position VARCHAR, date_of_birth TIMESTAMP)");
            s.execute("INSERT INTO players VALUES (2, 'Defender', TIMESTAMP '1999-01-05')");
        }
    }
}
