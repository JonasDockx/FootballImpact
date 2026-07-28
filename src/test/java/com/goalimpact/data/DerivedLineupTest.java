package com.goalimpact.data;

import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.EventRow;
import com.goalimpact.repair.LineupEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Item 17, slice 2, decisions 1 and 2. The maybe tier's derived lineup, against
// the real snapshot: a match with neither a team sheet nor an appearances record
// still has its own events, and they name a median of 18 of the 22 who played.
// The pure rules are pinned on plain lists in EventRosterTest and
// EditableMatchTest; this pins that the query feeds them the right rows.
class DerivedLineupTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    // A 2006 World Cup tie: the vendor carries its events but names almost none
    // of the men in them, so it exercises decision 2's labelled-by-id path.
    private static final long NAMELESS_GAME = 49333L;

    @TempDir
    Path tempDir;

    private SidecarStore vendorOnly() {
        return new SidecarStore(tempDir.resolve("absent.duckdb"), SNAPSHOT);
    }

    @Test
    void aMaybeMatchIsDerivedFromItsOwnEvents() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        EditableMatch match = vendorOnly().load(aMaybeGame());

        assertEquals(EditableMatch.Origin.EVENTS, match.origin());
        assertFalse(match.lineup().isEmpty(), "the events named nobody");
    }

    // Every derived row belongs to one of the two clubs that played, and no man
    // is on the pitch twice. Own goals are what make this worth asserting: taken
    // at face value they put 58 players on both sides of their own match.
    @Test
    void everyDerivedRowSitsOnExactlyOneOfTheTwoSides() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        EditableMatch match = vendorOnly().load(aMaybeGame());
        Set<Long> clubs = Set.of(match.header().homeClubId(), match.header().awayClubId());

        Set<Long> seen = new HashSet<>();
        for (LineupEntry entry : match.lineup()) {
            assertTrue(clubs.contains(entry.clubId()),
                entry + " belongs to neither club in " + clubs);
            assertTrue(seen.add(entry.playerId()), entry + " appears twice");
        }
    }

    // The start/bench split is read from the substitutions, so it must agree with
    // the events the very same match carries - the events that will later drive
    // the replay.
    @Test
    void theMenTheSubstitutionsBringOnAreTheOnesOnTheBench() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        EditableMatch match = vendorOnly().load(aMaybeGame());
        Set<Long> cameOn = new HashSet<>();
        for (EventRow event : match.events()) {
            if (EventRow.SUBSTITUTION.equals(event.type()) && event.playerInId() != null) {
                cameOn.add(event.playerInId());
            }
        }

        for (LineupEntry entry : match.lineup()) {
            assertEquals(!cameOn.contains(entry.playerId()), entry.starter(), entry.toString());
        }
    }

    // Decision 2: an id no vendor table names still goes in, labelled by that id.
    @Test
    void anIdTheVendorNeverNamesIsLabelledByItsId() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        List<LineupEntry> lineup = vendorOnly().load(NAMELESS_GAME).lineup();

        List<LineupEntry> unnamed = lineup.stream().filter(LineupEntry::unnamed).toList();
        assertFalse(unnamed.isEmpty(), "expected unnamed rows in game " + NAMELESS_GAME);
        for (LineupEntry entry : unnamed) {
            assertEquals(LineupEntry.unnamed(entry.playerId()), entry.playerName());
            assertEquals(LineupEntry.UNKNOWN_POSITION, entry.position());
        }
    }

    // A name is taken from wherever the snapshot holds one - players, another
    // match's team sheet, or an appearances row - so a man the vendor knows from
    // elsewhere is never reduced to his id.
    @Test
    void aManTheSnapshotNamesElsewhereArrivesWithHisName() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        long game = aMaybeGameWithANameableMan();
        assumeTrue(game > 0, "no maybe game has a nameable man");

        assertTrue(vendorOnly().load(game).lineup().stream().anyMatch(e -> !e.unnamed()),
            "game " + game + " named nobody");
    }

    // Fixtures derived at run time rather than pinned, so a snapshot refresh
    // cannot leave the test quoting a game that no longer has this shape.
    private long aMaybeGame() throws Exception {
        return firstGame("");
    }

    private long aMaybeGameWithANameableMan() throws Exception {
        return firstGame("""
            AND EXISTS (SELECT 1 FROM game_events e2
                JOIN players p ON p.player_id = e2.player_id
                WHERE CAST(e2.game_id AS BIGINT) = CAST(g.game_id AS BIGINT))
            """);
    }

    private long firstGame(String extra) throws Exception {
        String sql = """
            SELECT CAST(g.game_id AS BIGINT) AS gid FROM games g
            WHERE NOT EXISTS (SELECT 1 FROM game_lineups gl
                    WHERE CAST(gl.game_id AS BIGINT) = CAST(g.game_id AS BIGINT))
              AND NOT EXISTS (SELECT 1 FROM appearances ap
                    WHERE CAST(ap.game_id AS BIGINT) = CAST(g.game_id AS BIGINT))
              AND EXISTS (SELECT 1 FROM game_events e
                    WHERE CAST(e.game_id AS BIGINT) = CAST(g.game_id AS BIGINT))
            """ + extra + " ORDER BY gid LIMIT 1";

        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + SNAPSHOT, readOnly);
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong("gid") : -1L;
        }
    }
}
