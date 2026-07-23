package com.goalimpact.data;

import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SidecarOverrideTest {

    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // The vendor's Premier League opener: Manchester United 1-0 Fulham,
    // Zirkzee (435648) the 87th-minute winner.
    private static final long OPENER = 4361261L;

    // A sidecar DuckDB with the four mirror tables. status null writes the
    // schema only (an empty sidecar); otherwise it writes one coherent
    // replacement of the opener - 7-0, two full XIs, one goal by 900002.
    private static void writeSidecar(Path file, String status) throws Exception {
        String url = "jdbc:duckdb:" + file.toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE matches (
                  game_id BIGINT, status VARCHAR, date DATE,
                  competition_id VARCHAR, season VARCHAR, round VARCHAR,
                  competition_type VARCHAR,
                  home_club_id BIGINT, home_club_name VARCHAR,
                  away_club_id BIGINT, away_club_name VARCHAR,
                  home_club_goals INTEGER, away_club_goals INTEGER,
                  provenance VARCHAR, commit_hash VARCHAR)""");
            s.execute("""
                CREATE TABLE game_lineups (
                  game_id BIGINT, club_id BIGINT, player_id BIGINT,
                  player_name VARCHAR, position VARCHAR, "type" VARCHAR)""");
            s.execute("""
                CREATE TABLE game_events (
                  game_id BIGINT, minute INTEGER, "type" VARCHAR, club_id BIGINT,
                  player_id BIGINT, player_in_id BIGINT, description VARCHAR)""");
            s.execute("CREATE TABLE appearances (game_id BIGINT, minutes_played INTEGER)");
            if (status == null) {
                return;
            }
            s.execute("INSERT INTO matches VALUES (" + OPENER + ", '" + status + "',"
                + " DATE '2024-08-16', 'GB1', '2024', '1. Matchday', 'domestic_league',"
                + " 985, 'Sidecar United', 931, 'Sidecar City', 7, 0, 'test repair', 'abc123')");
            insertEleven(s, 985, 900001);
            insertEleven(s, 931, 900012);
            s.execute("INSERT INTO game_events VALUES (" + OPENER
                + ", 50, 'Goals', 985, 900002, NULL, 'test goal')");
        }
    }

    // Eleven starters for one club; the first id is the goalkeeper.
    private static void insertEleven(Statement s, long clubId, long firstId) throws Exception {
        for (int i = 0; i < 11; i++) {
            long pid = firstId + i;
            String position = i == 0 ? "Goalkeeper" : "Midfield";
            s.execute("INSERT INTO game_lineups VALUES (" + OPENER + ", " + clubId + ", "
                + pid + ", 'P" + pid + "', '" + position + "', 'starting_lineup')");
        }
    }

    private static Match opener(TransfermarktLoader loader) throws Exception {
        return loader.loadMatches("GB1", "2024").stream()
            .filter(m -> m.matchId() == OPENER).findFirst().orElseThrow();
    }

    private static MatchEvent.Goal firstGoal(List<MatchEvent> events) {
        return (MatchEvent.Goal) events.stream()
            .filter(e -> e instanceof MatchEvent.Goal).findFirst().orElseThrow();
    }

    @Test
    void aReleasedSidecarMatchWinsOverTheVendor(@TempDir Path dir) throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        Path sidecar = dir.resolve("sidecar.duckdb");
        writeSidecar(sidecar, "released");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT, sidecar)) {
            Match m = opener(loader);
            assertEquals("Sidecar United", m.home().name());   // the header is the sidecar's
            assertEquals(7, m.homeScore());

            List<MatchEvent> events = loader.loadEvents(m);
            long goals = events.stream().filter(e -> e instanceof MatchEvent.Goal).count();
            assertEquals(1, goals);                            // one sidecar goal, not Zirkzee's
            assertEquals(900002L, firstGoal(events).scorer().id());
        }
    }

    @Test
    void aDraftSidecarMatchDoesNotRate(@TempDir Path dir) throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        Path sidecar = dir.resolve("sidecar.duckdb");
        writeSidecar(sidecar, "draft");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT, sidecar)) {
            Match m = opener(loader);
            assertEquals("Manchester United", m.home().name());  // the vendor's copy, untouched
            assertEquals(1, m.homeScore());
            assertEquals(435648L, firstGoal(loader.loadEvents(m)).scorer().id());  // Zirkzee
        }
    }

    @Test
    void anEmptySidecarBehavesExactlyAsTheVendor(@TempDir Path dir) throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        Path sidecar = dir.resolve("sidecar.duckdb");
        writeSidecar(sidecar, null);   // schema present, no rows

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT, sidecar)) {
            List<MatchEvent> events = loader.loadEvents(opener(loader));
            assertEquals(14, events.size());                   // two XIs, ten subs, a goal, whistle
            assertEquals(435648L, firstGoal(events).scorer().id());
        }
    }
}
