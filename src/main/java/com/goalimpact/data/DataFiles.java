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
    // Overridable for the same reason `results` is: a build from a diagnostic
    // results file must be able to say so rather than quietly republishing
    // itself as the page the project stands behind.
    public static final Path VIEWER = pinned("goalimpact.viewer",
        "C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-viewer.html");

    // The match log's shards (#24), one per player_id % 256, loaded by the page
    // on demand. Fifth entry of ADR 0009's generated lifecycle and the first
    // that is a FOLDER: the population's log measures 222 MB against the page's
    // 12 MB, so it could not live in the page, and one player's is 50 rows, so
    // it does not have to. Disposable exactly like the page it sits beside.
    //
    // Defaulted BESIDE the viewer rather than pinned to an absolute path of its
    // own, because the page loads its shards by the relative path ledger/NNN.js
    // - so a diagnostic page built somewhere else must take its ledger with it
    // or it would read the designated run's rows under a diagnostic run's
    // chart. Overridable all the same, for the reason every path here is.
    public static final Path LEDGER = pinned("goalimpact.ledger",
        VIEWER.toAbsolutePath().resolveSibling("ledger").toString());

    // Attaching one of the three files beside another, read-only. It is here
    // because the trap it dodges is a property of these paths: DuckDB keeps ONE
    // database instance per file per JVM and every connection to that file
    // shares it, so an ATTACH is global rather than per-connection. The repair
    // tool opens two readers over the same results file, and the second one's
    // plain ATTACH ... AS vendor failed with "database with name vendor already
    // exists" - IF NOT EXISTS is what makes the two readers independent of the
    // order they happen to be built in.
    //
    // The alias resolves to the same file whichever reader wins the race,
    // because every caller is handed its paths from the constants above.
    public static String attachReadOnly(Path db, String alias) {
        return "ATTACH IF NOT EXISTS '" + db.toString().replace('\\', '/')
            + "' AS " + alias + " (READ_ONLY)";
    }

    private DataFiles() {
    }
}
