package com.goalimpact.data;

import com.goalimpact.model.Match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Decision 4, pinned. The editor restates the loader's usability gate rather than
// sharing a class with it, so a drift between the two would be silent until an
// 18-second replay. This drives real vendor matches through the real
// TransfermarktLoader and asserts the editor's problems() agrees: empty exactly
// when the loader rates the match, and its first reason equal to the reason the
// loader throws. The loader is opened vendor-only, so a match released in the
// real sidecar is still seen in its raw held state.
class LoaderAgreementTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    @TempDir
    Path tempDir;

    @Test
    void aCleanMatchIsCleanOnBothSides() throws Exception {
        assertAgrees(4361261L);   // Manchester United v Fulham, GB1 2024
    }

    @Test
    void noStartingGoalkeeperAgrees() throws Exception {
        assertAgrees(2367585L);   // CDR 2013
    }

    @Test
    void twoStartingGoalkeepersAgrees() throws Exception {
        assertAgrees(2465452L);   // NLP 2014
    }

    @Test
    void xiIsNotElevenAgrees() throws Exception {
        assertAgrees(2326902L);   // NLP 2013
    }

    // The editor's verdict on a game and the loader's must match.
    private void assertAgrees(long gameId) throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        // A never-written sidecar path, so load falls through to the vendor - the
        // same raw rows the loader reads.
        SidecarStore vendorStore = new SidecarStore(tempDir.resolve("absent.duckdb"), SNAPSHOT);
        List<String> editorProblems = vendorStore.load(gameId).problems();

        String loaderReason = loaderVerdict(gameId);
        if (loaderReason == null) {
            assertTrue(editorProblems.isEmpty(),
                "loader rates game " + gameId + " but editor reports " + editorProblems);
        } else {
            assertEquals(loaderReason, editorProblems.isEmpty() ? null : editorProblems.get(0),
                "game " + gameId);
        }
    }

    // Drive the one match through the real loader and return the reason it throws,
    // or null if it rates cleanly. loadMatches stages the whole slice, so
    // loadEvents can then be asked for this one match.
    private String loaderVerdict(long gameId) throws Exception {
        String[] slice = slice(gameId);
        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            Match match = loader.loadMatches(slice[0], slice[1]).stream()
                .filter(m -> m.matchId() == gameId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("game " + gameId + " not in its slice"));
            try {
                loader.loadEvents(match);
                return null;
            } catch (UnusableMatchException unusable) {
                return unusable.reason();
            }
        }
    }

    private String[] slice(long gameId) throws Exception {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + SNAPSHOT, readOnly);
            PreparedStatement ps = c.prepareStatement(
                "SELECT competition_id, season FROM games WHERE CAST(game_id AS BIGINT) = ?")) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no game " + gameId + " in the snapshot");
                }
                return new String[] { rs.getString("competition_id"), rs.getString("season") };
            }
        }
    }
}
