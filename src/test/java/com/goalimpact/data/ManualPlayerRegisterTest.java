package com.goalimpact.data;

import com.goalimpact.repair.AppearanceRow;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.LineupEntry;
import com.goalimpact.repair.ManualPlayer;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The manual_players register (item 17, slice 1; ADR 0012), on a throwaway sidecar
// under @TempDir. Every test writes, and save stamps commit_hash from the vendor's
// version table, so every test needs the snapshot and skips cleanly without it.
class ManualPlayerRegisterTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    private static final long GAME = 999000002L;
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

    // A match one man short of a lawful home XI - the shape the picker exists for.
    private static EditableMatch tenAndAFullSide() {
        List<LineupEntry> lineup = new ArrayList<>();
        lineup.add(new LineupEntry(HOME, 100, "P100", "Goalkeeper", LineupEntry.STARTER));
        for (int i = 1; i < 10; i++) {
            lineup.add(new LineupEntry(HOME, 100 + i, "P" + (100 + i), "Centre-Back",
                LineupEntry.STARTER));
        }
        lineup.add(new LineupEntry(AWAY, 200, "P200", "Goalkeeper", LineupEntry.STARTER));
        for (int i = 1; i < 11; i++) {
            lineup.add(new LineupEntry(AWAY, 200 + i, "P" + (200 + i), "Centre-Back",
                LineupEntry.STARTER));
        }
        MatchHeader header = new MatchHeader(GAME, LocalDate.of(2014, 4, 30), "GRP",
            "2013", "Quarter-Finals", "domestic_cup", HOME, "Olympiakos Volos",
            AWAY, "Panathinaikos", 0, 1);
        return new EditableMatch(header, lineup, List.of(), List.of(new AppearanceRow(GAME, 90)));
    }

    @Test
    void savingAMatchWithACreatedPlayerWritesHisRegisterRow() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        EditableMatch match = tenAndAFullSide()
            .withManualIdCeiling(store.highestManualPlayerId())
            .create(HOME, "Marc Dupont", "Winger", LocalDate.of(1975, 3, 1), "club programme");

        store.save(match, "released", match.provenanceSummary());

        assertEquals(1, count(db, "SELECT count(*) FROM manual_players"));
        assertEquals("Marc Dupont",
            text(db, "SELECT player_name FROM manual_players"));
        assertEquals(ManualPlayer.FIRST_ID,
            count(db, "SELECT player_id FROM manual_players"));
        assertEquals(1, count(db, "SELECT count(*) FROM game_lineups WHERE player_id = "
            + ManualPlayer.FIRST_ID));
        assertEquals(1, count(db,
            "SELECT count(*) FROM manual_players WHERE created_on IS NOT NULL"));
    }

    // ADR 0012, decision 3: the register is written with the match and never on the
    // create click, so an abandoned repair leaves nothing behind. Creating without
    // saving is the whole test.
    @Test
    void anAbandonedRepairLeavesNoRegisterRow() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        tenAndAFullSide().create(HOME, "Marc Dupont", "Winger", null, "");

        store.save(tenAndAFullSide(), "draft", "no created players");

        assertEquals(0, count(db, "SELECT count(*) FROM manual_players"));
    }

    // The allocation invariant: the ceiling read at open is max+1, so a second
    // session never re-mints an id the first one handed out. That is precisely the
    // split - one man, two careers, half the exposure each - that ADR 0012 exists
    // to prevent.
    @Test
    void aSecondSessionAllocatesAboveTheFirst() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);

        EditableMatch first = tenAndAFullSide()
            .withManualIdCeiling(store.highestManualPlayerId())
            .create(HOME, "Marc Dupont", "Winger", null, "");
        store.save(first, "released", "one");

        assertEquals(ManualPlayer.FIRST_ID, store.highestManualPlayerId());

        EditableMatch second = tenAndAFullSide()
            .withManualIdCeiling(store.highestManualPlayerId())
            .create(HOME, "Jan Peeters", "Winger", null, "");
        assertEquals(ManualPlayer.FIRST_ID + 1, second.created().get(0).playerId());

        store.save(second, "released", "two");
        assertEquals(2, count(db, "SELECT count(*) FROM manual_players"));
    }

    @Test
    void aDateOfBirthIsOptionalAndStoredAsNull() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        EditableMatch match = tenAndAFullSide()
            .create(HOME, "Marc Dupont", "Winger", null, "found in a club programme");
        store.save(match, "released", "p");

        assertNull(text(db, "SELECT CAST(date_of_birth AS VARCHAR) FROM manual_players"));
        assertEquals("found in a club programme", text(db, "SELECT note FROM manual_players"));
    }

    // A re-save of the same match must not duplicate the register row - the match's
    // own rows are deleted and rewritten, but a manual player is not per-match state.
    @Test
    void reSavingTheSameMatchDoesNotDuplicateTheRegisterRow() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        EditableMatch match = tenAndAFullSide()
            .create(HOME, "Marc Dupont", "Winger", null, "");
        store.save(match, "draft", "p");
        store.save(match, "released", "p");

        assertEquals(1, count(db, "SELECT count(*) FROM manual_players"));
    }

    // Before the first repair there is no file at all, and a sidecar written before
    // ADR 0012 has four tables and no register. Both must read as an empty register
    // rather than as an error, or the editor cannot open.
    @Test
    void anAbsentSidecarHasAnEmptyRegister() throws Exception {
        assertEquals(0, new SidecarStore(sidecar(), SNAPSHOT).highestManualPlayerId());
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
