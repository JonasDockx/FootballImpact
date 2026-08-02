package com.goalimpact.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SnapshotSmokeTest {
    
    // The vendor snapshot is 195 MiB and lives outside the repo, so this
    // test skips where it is absent. Where it is present, it fails on the
    // first line of the first query if the pinned driver cannot read the
    // file's storage format - rather than three layers deep in a replay.
    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    @Test
    void readsTheVendorSnapshot() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        try (Connection connection = 
            DriverManager.getConnection("jdbc:duckdb:" + SNAPSHOT, readOnly);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM games")) {
                rows.next();
                assertEquals(88_958, rows.getLong(1));
            }
    }

    // ADR 0016 reaches for a column nothing in Java had ever read, in a table
    // where it is stored as text. This is the smoke test for that one fact:
    // the dates parse, there are plenty of them, and none of them is absurd.
    // Ranges rather than a pinned count, because item 30 is still widening the
    // snapshot underneath this.
    @Test
    void readsEveryDateOfBirthItCan() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            Map<Long, LocalDate> born = loader.birthDates();

            // ADR 0011 counted 40,364 rows in players; a date that fails to
            // parse is silently dropped, so a broken cast would show up here
            // as a collapse rather than as an error.
            assertTrue(born.size() > 35_000, "only " + born.size() + " dates of birth");
            LocalDate today = LocalDate.now();
            for (LocalDate date : born.values()) {
                assertTrue(date.isAfter(LocalDate.of(1900, 1, 1)) && date.isBefore(today),
                    "implausible date of birth: " + date);
            }
        }
    }
}
