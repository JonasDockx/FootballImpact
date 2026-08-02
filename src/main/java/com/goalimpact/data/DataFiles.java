package com.goalimpact.data;

import java.nio.file.Path;

// ADR 0009's three DuckDB files, named once so the engine (Main) and the repair
// tool (GuiMain) cannot drift apart on where they live. They belong in data
// because a file location is exactly the kind of thing CLAUDE.md keeps in this
// package - the gui package holds no such knowledge, and neither does the
// engine any more.
//
// All three sit in one directory on purpose: a single DuckDB connection can
// attach all three at once (ADR 0009). None is versioned source - two are
// rebuilt at will and one is precious hand-made state - so all three live
// outside the repo. The viewer page below is generated from two of them and
// lives beside them for the same reason.
public final class DataFiles {

    // Each path below can be pointed somewhere else for ONE run with a -D on the
    // command line. This is not a configuration system and must not become one:
    // the pinned value is still the answer, and a run that overrides nothing is
    // byte-for-byte the run it always was.
    //
    // It exists because item 30 stages a widening across several passes and the
    // ruler moves only once (ADR 0013). Between passes there is a rebuilt
    // snapshot nobody is ready to ship, and the only way to look at it was to
    // edit this file, run, and remember to edit it back - which is a pin that
    // silently is not one. A diagnostic run now says so at the call site:
    //
    //   mvn compile exec:java "-Dgoalimpact.snapshot=.../transfermarkt-datasets-stage3.duckdb"
    //                         "-Dgoalimpact.results=.../goalimpact-results-stage3.duckdb"
    //                         "-Dgoalimpact.csv=goalimpact-stage3.csv"
    //
    // Overriding `results` matters as much as overriding `snapshot`: the results
    // DB is rewritten whole by every run and the viewer and the repair worklist
    // read it, so a diagnostic run that left it pointed at the live file would
    // quietly republish itself as the designated one.
    private static Path pinned(String property, String path) {
        return Path.of(System.getProperty(property, path));
    }

    // The vendor snapshot: read-only, the rating spine.
    public static final Path SNAPSHOT = pinned("goalimpact.snapshot",
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // The sidecar of hand-made repairs (item 26). Absent until the first repair,
    // so the loader attaches nothing and the replay stays byte-identical until a
    // match is actually released.
    public static final Path SIDECAR = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb");

    // The disposable results DB (ADR 0011's first use of it), rebuilt whole every
    // designated run. Holds the worklist the repair tool reads.
    public static final Path RESULTS = pinned("goalimpact.results",
        "C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb");

    // The viewer page (#22), fourth file of ADR 0009's generated lifecycle and
    // the only one that is not a database. Built from the two files above by a
    // step of its own, so rebuilding it after a band or constant change costs
    // seconds rather than a replay. Disposable like the results DB.
    public static final Path VIEWER = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-viewer.html");

    private DataFiles() {
    }
}
