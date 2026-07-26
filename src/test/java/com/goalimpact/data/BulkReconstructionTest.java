package com.goalimpact.data;

import com.goalimpact.repair.EditableMatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The bulk reconstruction's selection and batch write (item 26, stage 4b-4, gate
// check 1), on a throwaway sidecar under @TempDir. Enumeration reads the real
// vendor, so these skip cleanly when the snapshot is absent.
class BulkReconstructionTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    private static final long APPEARED_GAME = 3839821L;   // Lyon v Troyes, no team sheet
    private static final long SHEET_GAME = 4361261L;      // Man Utd v Fulham, has a sheet

    @TempDir
    Path tempDir;

    private SidecarStore store() {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        return new SidecarStore(tempDir.resolve("sidecar.duckdb"), SNAPSHOT);
    }

    @Test
    void appearedIdsHoldTheNoSheetGamesAndNotTheSheetGames() throws Exception {
        List<Long> ids = store().appearedGameIds();
        assertTrue(ids.contains(APPEARED_GAME), "an appeared game should be listed");
        assertFalse(ids.contains(SHEET_GAME), "a game with a vendor sheet should not be");
    }

    @Test
    void theBatchStampsProvenanceAndReloadsFromTheSidecar() throws Exception {
        SidecarStore store = store();
        List<EditableMatch> clean = store.reconstructAppeared(List.of(APPEARED_GAME));
        assertEquals(EditableMatch.Origin.RECONSTRUCTED, clean.get(0).origin());
        assertTrue(clean.get(0).problems().isEmpty());

        store.saveAll(clean, "released", "[bulk test]");

        assertTrue(store.sidecarGameIds().contains(APPEARED_GAME));
        String provenance = provenanceOf(store, APPEARED_GAME);
        assertTrue(provenance.contains("[bulk test]"), provenance);
        assertTrue(provenance.contains("reconstructed"), provenance);
    }

    @Test
    void alreadyPresentGamesAreVisibleForExclusion() throws Exception {
        SidecarStore store = store();
        store.saveAll(store.reconstructAppeared(List.of(APPEARED_GAME)), "released", "");
        assertTrue(store.sidecarGameIds().contains(APPEARED_GAME));
    }

    private String provenanceOf(SidecarStore store, long gameId) throws Exception {
        try (Connection c = openReadOnly(tempDir.resolve("sidecar.duckdb"));
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery(
                "SELECT provenance FROM matches WHERE game_id = " + gameId)) {
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
