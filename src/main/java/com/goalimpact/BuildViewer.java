package com.goalimpact;

import com.goalimpact.data.DataFiles;
import com.goalimpact.report.LedgerWriter;
import com.goalimpact.report.ViewerWriter;

import java.util.Locale;

// The viewer's build step (#22), separate from the replay by decision (#46):
// it replays nothing, so rebuilding the page after a threshold, band or
// constant change costs seconds. It reads the results file and the vendor
// snapshot read-only and writes the page and the match log beside it.
//
//   mvn compile exec:java -Dexec.mainClass=com.goalimpact.BuildViewer
//
// TWO steps since #24, and being a step at all is what makes the second one
// affordable: the match log is 2.2M rows and it is built from the same two
// files by the same reading, so it costs seconds here where it would cost a
// replay anywhere else.
//
// The page is written FIRST and the log second, on purpose. A viewer with a
// stale log states so and draws anyway - the log's guards are deliberately
// weaker than the page's (#24) - where a log with no page is nothing at all.
// So the order is the one whose half-finished state is the survivable one.
//
// Lives here rather than in report because this is where the file locations
// are known - report is handed paths and knows nothing about where they are.
public final class BuildViewer {

    private BuildViewer() {
    }

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        ViewerWriter.Result page =
            ViewerWriter.write(DataFiles.RESULTS, DataFiles.SNAPSHOT, DataFiles.VIEWER);

        System.out.printf(Locale.US,
            "Viewer: %,d players, %,d monthly points, %,d club bands, %.1f MB -> %s%n",
            page.players(), page.points(), page.bands(), page.bytes() / 1e6,
            DataFiles.VIEWER.toAbsolutePath());

        LedgerWriter.Result log =
            LedgerWriter.write(DataFiles.RESULTS, DataFiles.SNAPSHOT, DataFiles.LEDGER);

        System.out.printf(Locale.US,
            "Match log: %,d players, %,d rows over %d shards, %.1f MB -> %s%n",
            log.players(), log.rows(), log.shards(), log.bytes() / 1e6,
            DataFiles.LEDGER.toAbsolutePath());
        System.out.printf(Locale.US, "Run %s, matches through %s (%.1fs)%n",
            page.runId(), page.lastMatchDate(), (System.nanoTime() - start) / 1e9);
    }
}
