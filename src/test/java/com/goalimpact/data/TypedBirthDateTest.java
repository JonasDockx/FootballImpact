package com.goalimpact.data;

import com.goalimpact.repair.AppearanceRow;
import com.goalimpact.repair.EditableMatch;
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
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The standalone birthday write (ADR 0012, amendment of 2026-08-04, decisions 8
// and 9), on a throwaway sidecar under @TempDir.
//
// STAGE 1 (#53) LANDS THIS INERT: the method exists and is proved here, and
// nothing in the GUI or the replay calls it. Stage 2 (#54) turns it on. So these
// tests are the whole of its coverage until then, and they must pin the two
// things the amendment actually decided - the row is written under the player's
// OWN id with a NULL name, and a second typing overwrites the first.
class TypedBirthDateTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    private static final long GAME = 999000003L;
    private static final long HOME = 100L;
    private static final long AWAY = 200L;

    // A vendor id the snapshot names: Shinji Okazaki, the top of #53's ranked
    // list - a vendor `players` row with an empty date of birth.
    private static final long VENDOR_ID = 79642L;

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireSnapshot() {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
    }

    private Path sidecar() {
        return tempDir.resolve("sidecar.duckdb");
    }

    // Decision 8: his own id, and player_name NULL so the vendor's name still
    // wins. Every name reader splices mp.player_name as the first arm of a
    // COALESCE, so a NULL falls through and naming is untouched - which is what
    // stops a typed birthday from freezing a name the vendor may later correct.
    @Test
    void aTypedDateIsARegisterRowUnderHisOwnIdWithNoName() throws Exception {
        Path db = sidecar();
        new SidecarStore(db, SNAPSHOT)
            .setBirthDate(VENDOR_ID, LocalDate.of(1986, 4, 16), "Wikipedia");

        assertEquals(1, count(db, "SELECT count(*) FROM manual_players"));
        assertEquals(VENDOR_ID, count(db, "SELECT player_id FROM manual_players"));
        assertNull(text(db, "SELECT player_name FROM manual_players"));
        assertEquals("1986-04-16",
            text(db, "SELECT CAST(date_of_birth AS VARCHAR) FROM manual_players"));
        assertEquals("Wikipedia", text(db, "SELECT note FROM manual_players"));
        assertEquals(1, count(db,
            "SELECT count(*) FROM manual_players WHERE created_on IS NOT NULL"));
    }

    // Decision 8 again: it MAY overwrite. A mistyped date must be re-typeable,
    // which is the one way this write differs from the register write that rides
    // inside save's transaction and adds nothing when the row is already there.
    @Test
    void typingASecondDateReplacesTheFirst() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.setBirthDate(VENDOR_ID, LocalDate.of(1986, 4, 16), "guessed");
        store.setBirthDate(VENDOR_ID, LocalDate.of(1986, 4, 15), "Transfermarkt");

        assertEquals(1, count(db, "SELECT count(*) FROM manual_players"));
        assertEquals("1986-04-15",
            text(db, "SELECT CAST(date_of_birth AS VARCHAR) FROM manual_players"));
        assertEquals("Transfermarkt", text(db, "SELECT note FROM manual_players"));
    }

    // A man already in the register because a repair named him keeps that name:
    // this write touches the date column and nothing else. Otherwise typing a
    // birthday would silently un-name a player, which is decision 7's whole
    // point in reverse.
    @Test
    void datingAnAlreadyNamedPlayerKeepsHisName() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.save(tenAndAFullSide()
            .withManualIdCeiling(store.highestManualPlayerId())
            .name(105L, "Marc Dupont", null, "matchday programme"), "released", "named one");

        store.setBirthDate(105L, LocalDate.of(1975, 3, 1), "club programme");

        assertEquals(1, count(db, "SELECT count(*) FROM manual_players"));
        assertEquals("Marc Dupont", text(db, "SELECT player_name FROM manual_players"));
        assertEquals("1975-03-01",
            text(db, "SELECT CAST(date_of_birth AS VARCHAR) FROM manual_players"));
    }

    // Decision 8's narrowed invariant: a vendor-id row with no sidecar match
    // behind it is lawful now, and it must stay invisible to the id allocator -
    // otherwise typing one birthday would drag the next CREATED player's id up
    // to a vendor's. The range test is the honest filter.
    @Test
    void aTypedDateDoesNotMoveTheAllocator() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.setBirthDate(VENDOR_ID, LocalDate.of(1986, 4, 16), "");

        assertEquals(0, store.highestManualPlayerId());
        assertEquals(0, count(db, "SELECT count(*) FROM matches"));
    }

    // The note is optional and never blocks the save (#52 decision 5): a date
    // with no source is still better than the population-average penalty.
    @Test
    void theNoteIsOptional() throws Exception {
        Path db = sidecar();
        new SidecarStore(db, SNAPSHOT).setBirthDate(VENDOR_ID, LocalDate.of(1986, 4, 16), null);

        assertNull(text(db, "SELECT note FROM manual_players"));
        assertEquals("1986-04-16",
            text(db, "SELECT CAST(date_of_birth AS VARCHAR) FROM manual_players"));
    }

    // The read side the pane merges into its rows, on ClubPane's precedent: the
    // ranked list comes from the results file and what has been typed comes from
    // the sidecar, so a row filled this afternoon stops looking like work at once.
    @Test
    void typedDatesAreReadBackByPlayerId() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.setBirthDate(VENDOR_ID, LocalDate.of(1986, 4, 16), "Wikipedia");

        Map<Long, TypedBirthDate> typed = store.typedBirthDates();

        assertEquals(1, typed.size());
        assertEquals(LocalDate.of(1986, 4, 16), typed.get(VENDOR_ID).dateOfBirth());
        assertEquals("Wikipedia", typed.get(VENDOR_ID).note());
    }

    // A name-only register row asserts nothing about a birthday, so it must not
    // appear as one - it is exactly the 2,969-id population of the 2026-07-28
    // amendment, and reporting them as done would hide real work.
    @Test
    void aNameOnlyRegisterRowIsNotATypedDate() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.save(tenAndAFullSide()
            .withManualIdCeiling(store.highestManualPlayerId())
            .name(105L, "Marc Dupont", null, ""), "released", "named one");

        assertTrue(store.typedBirthDates().isEmpty());
    }

    // The other half of the same short-lived read. A hand-typed name beats the
    // vendor's and the lineups' alike (ADR 0012 decision 7), and a
    // birthday-only row carries none - which is exactly what lets the vendor's
    // name keep winning for a man whose birthday was merely typed.
    @Test
    void onlyNamedRowsAreRegisteredNames() throws Exception {
        Path db = sidecar();
        SidecarStore store = new SidecarStore(db, SNAPSHOT);
        store.save(tenAndAFullSide()
            .withManualIdCeiling(store.highestManualPlayerId())
            .name(105L, "Marc Dupont", null, ""), "released", "named one");
        store.setBirthDate(VENDOR_ID, LocalDate.of(1986, 4, 16), "Wikipedia");

        Map<Long, String> names = store.registeredNames();

        assertEquals(1, names.size());
        assertEquals("Marc Dupont", names.get(105L));
        assertNull(names.get(VENDOR_ID));
    }

    // Before the first repair there is no file at all, and a sidecar written
    // before ADR 0012 has four tables and no register. Both must read as nothing
    // typed rather than as an error, or the pane cannot open.
    @Test
    void anAbsentSidecarHasNoTypedDates() throws Exception {
        assertTrue(new SidecarStore(sidecar(), SNAPSHOT).typedBirthDates().isEmpty());
    }

    // The same match shape ManualPlayerRegisterTest uses: one man short of a
    // lawful home XI, so a name() is accepted.
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
