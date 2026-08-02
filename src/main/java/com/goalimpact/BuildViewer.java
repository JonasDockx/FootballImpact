package com.goalimpact;

import com.goalimpact.data.DataFiles;
import com.goalimpact.report.ViewerWriter;

import java.util.Locale;

// The viewer's build step (#22), separate from the replay by decision (#46):
// it replays nothing, so rebuilding the page after a threshold, band or
// constant change costs seconds. It reads the results file and the vendor
// snapshot read-only and writes one HTML file.
//
//   mvn compile exec:java -Dexec.mainClass=com.goalimpact.BuildViewer
//
// Lives here rather than in report because this is where the file locations
// are known - report is handed paths and knows nothing about where they are.
public final class BuildViewer {

    private BuildViewer() {
    }

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        ViewerWriter.Result result =
            ViewerWriter.write(DataFiles.RESULTS, DataFiles.SNAPSHOT, DataFiles.VIEWER);

        System.out.printf(Locale.US,
            "Viewer: %,d players, %,d monthly points, %.1f MB -> %s%n",
            result.players(), result.points(), result.bytes() / 1e6,
            DataFiles.VIEWER.toAbsolutePath());
        System.out.printf(Locale.US, "Run %s, matches through %s (%.1fs)%n",
            result.runId(), result.lastMatchDate(), (System.nanoTime() - start) / 1e9);
    }
}
