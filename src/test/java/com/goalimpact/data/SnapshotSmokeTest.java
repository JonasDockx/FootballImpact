package com.goalimpact.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
