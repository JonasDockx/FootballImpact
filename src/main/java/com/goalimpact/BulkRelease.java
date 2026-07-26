package com.goalimpact;

import com.goalimpact.data.DataFiles;
import com.goalimpact.data.SidecarStore;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.MatchHeader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

// Bulk reconstruction release (item 26, stage 4b-4). Releases every appeared game
// that reconstructs cleanly, instead of one double-click at a time - defensible
// only for the clean subset, whose eleven are derived mechanically and whose
// provenance the generated seed fully describes (grill 2026-07-26).
//
// It reuses SidecarStore's own load and save paths through the new batch methods,
// so a row it writes is byte-identical to one the editor writes. It is deliberately
// two-step: with no argument it is a DRY RUN - it counts, prints a random sample to
// eyeball, and writes nothing; only 'commit' backs up the sidecar and writes. Every
// released row carries a dated tag so the whole batch is undoable as one set.
public final class BulkRelease {

    private static final String STAMP = "[bulk reconstruction " + LocalDate.now() + "]";
    private static final int SAMPLE = 10;

    public static void main(String[] args) throws Exception {
        boolean commit = args.length > 0 && args[0].equalsIgnoreCase("commit");
        SidecarStore store = new SidecarStore(DataFiles.SIDECAR, DataFiles.SNAPSHOT);

        List<Long> appeared = store.appearedGameIds();
        Set<Long> already = store.sidecarGameIds();
        List<Long> todo = appeared.stream().filter(id -> !already.contains(id)).toList();

        List<EditableMatch> reconstructed = store.reconstructAppeared(todo);
        List<EditableMatch> clean = reconstructed.stream()
            .filter(m -> m.problems().isEmpty())
            .toList();

        System.out.println("appeared games: " + appeared.size()
            + "  (" + (appeared.size() - todo.size())
            + " already in the sidecar, skipped)");
        System.out.println("reconstruct clean and releasable: " + clean.size());
        System.out.println("reconstruct with problems, left for the editor: "
            + (reconstructed.size() - clean.size()));
        printSample(clean);

        if (!commit) {
            System.out.println();
            System.out.println("DRY RUN - nothing written.");
            System.out.println("To release, re-run with:  "
                + "mvn compile exec:java -Dexec.mainClass=com.goalimpact.BulkRelease "
                + "-Dexec.args=commit");
            return;
        }

        Path backup = backup();
        System.out.println();
        System.out.println("sidecar backed up to " + backup);
        store.saveAll(clean, "released", STAMP);
        System.out.println("released " + clean.size() + " matches, each tagged " + STAMP);
        System.out.println("to undo this batch, delete from the four sidecar tables where "
            + "game_id is in (select game_id from matches where provenance like '%"
            + STAMP + "%')");
        System.out.println("now re-run the replay:  mvn compile exec:java");
    }

    private static void printSample(List<EditableMatch> clean) {
        if (clean.isEmpty()) {
            return;
        }
        List<EditableMatch> sample = new ArrayList<>(clean);
        Collections.shuffle(sample, new Random(42));   // fixed seed: a reproducible eyeball
        System.out.println();
        System.out.println("sample of " + Math.min(SAMPLE, sample.size())
            + " reconstructed XIs:");
        sample.stream().limit(SAMPLE).forEach(BulkRelease::printMatch);
    }

    private static void printMatch(EditableMatch m) {
        MatchHeader h = m.header();
        System.out.println("  " + h.gameId() + "  " + h.date() + " " + h.competitionId()
            + "  " + h.homeClubName() + " v " + h.awayClubName()
            + "  (" + h.homeClubGoals() + "-" + h.awayClubGoals() + ")");
        System.out.println("      home " + side(m, h.homeClubId())
            + "   |   away " + side(m, h.awayClubId()));
    }

    private static String side(EditableMatch m, long clubId) {
        long starters = m.lineup().stream()
            .filter(e -> e.clubId() == clubId && e.starter())
            .count();
        String keeper = m.lineup().stream()
            .filter(e -> e.clubId() == clubId && e.starter() && e.goalkeeper())
            .map(e -> e.playerName() + " (" + e.playerId() + ")")
            .findFirst()
            .orElse("unresolved");
        return "XI(" + starters + ") GK " + keeper;
    }

    private static Path backup() throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = DataFiles.SIDECAR.resolveSibling(
            "transfermarkt-sidecar.bak-" + ts + ".duckdb");
        Files.copy(DataFiles.SIDECAR, backup, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
    }

    private BulkRelease() {
    }
}
