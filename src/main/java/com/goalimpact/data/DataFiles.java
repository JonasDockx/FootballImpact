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

    // The vendor snapshot: read-only, 88,958 games, the rating spine.
    public static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // The sidecar of hand-made repairs (item 26). Absent until the first repair,
    // so the loader attaches nothing and the replay stays byte-identical until a
    // match is actually released.
    public static final Path SIDECAR = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb");

    // The disposable results DB (ADR 0011's first use of it), rebuilt whole every
    // designated run. Holds the worklist the repair tool reads.
    public static final Path RESULTS = Path.of(
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
