package com.goalimpact.data;

import com.goalimpact.repair.EditableMatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Gate check 1 for stage 4b-3: the appeared reconstruction, against a real match.
// Game 3839821 (Olympique Lyon 4-1 Troyes, FR1 2022-08-19) has no team sheet, so
// SidecarStore.load falls through to reconstruction: roster from appearances,
// positions from players, starters = everyone the substitution events did not
// name coming on. A never-written sidecar path forces the vendor path. Hand
// verified 2026-07-26: Lyon 11 + 5 bench (GK Remy Riou, 18940), Troyes 11 + 4
// bench (GK Gauthier Gallon, 193256).
class AppearedReconstructionTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    private static final long GAME = 3839821L;
    private static final long LYON = 1041L;
    private static final long TROYES = 1095L;
    private static final long RIOU = 18940L;
    private static final long GALLON = 193256L;

    @TempDir
    Path tempDir;

    private EditableMatch reconstructed() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        // A path that was never written, so load() cannot find a draft and reads
        // the vendor - the same rows the engine holds.
        return new SidecarStore(tempDir.resolve("absent.duckdb"), SNAPSHOT).load(GAME);
    }

    @Test
    void anAppearedMatchReconstructsToAValidElevenEach() throws Exception {
        EditableMatch match = reconstructed();

        assertEquals(EditableMatch.Origin.RECONSTRUCTED, match.origin());
        assertTrue(match.problems().isEmpty(), match.problems().toString());
        assertEquals(31, match.lineup().size());          // 16 Lyon + 15 Troyes
        assertEquals(11, starters(match, LYON));
        assertEquals(11, starters(match, TROYES));
    }

    @Test
    void theStartingKeepersAreTheOnesTheRecordShows() throws Exception {
        EditableMatch match = reconstructed();
        assertTrue(startedGoalkeeper(match, LYON, RIOU),
            "Riou should be Lyon's starting keeper");
        assertTrue(startedGoalkeeper(match, TROYES, GALLON),
            "Gallon should be Troyes' starting keeper");
    }

    @Test
    void thePlayersWhoCameOnAreBenched() throws Exception {
        EditableMatch match = reconstructed();
        // Jeff Reine-Adelaide (Lyon) and Tristan Dingome (Troyes) both came on.
        assertTrue(benched(match, 326300L), "Reine-Adelaide came on, so is bench");
        assertTrue(benched(match, 126683L), "Dingome came on, so is bench");
    }

    private static long starters(EditableMatch match, long clubId) {
        return match.lineup().stream()
            .filter(e -> e.clubId() == clubId && e.starter())
            .count();
    }

    private static boolean startedGoalkeeper(EditableMatch match, long clubId, long playerId) {
        return match.lineup().stream()
            .anyMatch(e -> e.clubId() == clubId && e.playerId() == playerId
                && e.starter() && e.goalkeeper());
    }

    private static boolean benched(EditableMatch match, long playerId) {
        return match.lineup().stream()
            .filter(e -> e.playerId() == playerId)
            .findFirst()
            .map(e -> !e.starter())
            .orElse(false);
    }
}
