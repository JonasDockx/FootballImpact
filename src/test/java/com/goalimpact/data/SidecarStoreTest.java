package com.goalimpact.data;

import com.goalimpact.repair.AppearanceRow;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.EventRow;
import com.goalimpact.repair.LineupEntry;
import com.goalimpact.repair.MatchHeader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The writer's own checks (item 26, stage 4b-2, gate check 3), all on a throwaway
// sidecar under @TempDir so the precious file is never touched. Every test writes,
// so every test needs the vendor snapshot - save stamps commit_hash from its
// version table - and skips cleanly where the snapshot is absent, keeping mvn test
// runnable with no database.
class SidecarStoreTest {

    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    private static final long GAME = 999000001L;
    private static final long HOME = 100L;
    private static final long AWAY = 200L;

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireSnapshot() {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
    }

    private Path sidecar() {
        return tempDir.resolve("sidecar.duckdb");
    }

    private static LineupEntry starter(long club, long id, String position) {
        return new LineupEntry(club, id, "P" + id, position, "starting_lineup");
    }

    // A lawful synthetic match, built in the order the reader returns it - lineups
    // by club then player, events by minute, appearances by minutes - so a
    // reloaded copy compares equal list-for-list.
    private static EditableMatch sampleMatch() {
        List<LineupEntry> lineup = new ArrayList<>();
        lineup.add(starter(HOME, 100, "Goalkeeper"));
        for (int i = 1; i < 11; i++) {
            lineup.add(starter(HOME, 100 + i, "Centre-Back"));
        }
        lineup.add(starter(AWAY, 200, "Goalkeeper"));
        for (int i = 1; i < 11; i++) {
            lineup.add(starter(AWAY, 200 + i, "Centre-Back"));
        }

        // The first event's player_in_id is null, the second's is set: the round
        // trip must keep the null a null.
        List<EventRow> events = List.of(
            new EventRow(GAME, 23, "Goals", AWAY, 205L, null, "0:1"),
            new EventRow(GAME, 60, "Substitutions", HOME, 108L, 109L, "in for out"));

        List<AppearanceRow> appearances = List.of(
            new AppearanceRow(GAME, 75), new AppearanceRow(GAME, 90), new AppearanceRow(GAME, 90));

        MatchHeader header = new MatchHeader(GAME, LocalDate.of(2014, 4, 30), "GRP",
            "2013", "Quarter-Finals", "domestic_cup", HOME, "Olympiakos Volos",
            AWAY, "Panathinaikos", 0, 1);
        return new EditableMatch(header, lineup, events, appearances);
    }

    @Test
    void createsTheFourTablesAndStoresTheMatch() throws Exception {
        Path db = sidecar();
        new SidecarStore(db, SNAPSHOT).save(sampleMatch(), "released", "test provenance");

        assertEquals(1, count(db, "SELECT count(*) FROM matches WHERE game_id = " + GAME));
        assertEquals(22, count(db, "SELECT count(*) FROM game_lineups WHERE game_id = " + GAME));
        assertEquals(2, count(db, "SELECT count(*) FROM game_events WHERE game_id = " + GAME));
        assertEquals(3, count(db, "SELECT count(*) FROM appearances WHERE game_id = " + GAME));

        assertNotNull(text(db, "SELECT commit_hash FROM matches WHERE game_id = " + GAME));
    }

    @Test
    void aSecondSaveReplacesRatherThanDuplicates() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.save(sampleMatch(), "draft", "first");
        store.save(sampleMatch(), "released", "second");

        assertEquals(1, count(db, "SELECT count(*) FROM matches WHERE game_id = " + GAME));
        assertEquals(22, count(db, "SELECT count(*) FROM game_lineups WHERE game_id = " + GAME));
        assertEquals(2, count(db, "SELECT count(*) FROM game_events WHERE game_id = " + GAME));
        assertEquals(3, count(db, "SELECT count(*) FROM appearances WHERE game_id = " + GAME));
    }

    @Test
    void theStatusIsWrittenAsGiven() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.save(sampleMatch(), "draft", "p");
        assertEquals("draft", text(db, "SELECT status FROM matches WHERE game_id = " + GAME));
        store.save(sampleMatch(), "released", "p");
        assertEquals("released", text(db, "SELECT status FROM matches WHERE game_id = " + GAME));
    }

    @Test
    void aSavedDraftReloadsIdentically() throws Exception {
        SidecarStore store = new SidecarStore(sidecar(), SNAPSHOT);
        EditableMatch saved = sampleMatch();
        store.save(saved, "draft", "p");

        EditableMatch loaded = store.load(GAME);
        assertEquals(saved.header(), loaded.header());
        assertEquals(saved.lineup(), loaded.lineup());
        assertEquals(saved.events(), loaded.events());
        assertEquals(saved.appearances(), loaded.appearances());
    }

    private static long count(Path db, String sql) throws Exception {
        try (Connection c = openReadOnly(db);
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(Path db, String sql) throws Exception {
        try (Connection c = openReadOnly(db);
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection openReadOnly(Path db) throws Exception {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + db, readOnly);
    }
}
