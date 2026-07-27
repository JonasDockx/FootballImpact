package com.goalimpact.data;

import com.goalimpact.repair.AppearanceRow;
import com.goalimpact.repair.CandidateRanker;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.LineupEntry;
import com.goalimpact.repair.ManualPlayer;
import com.goalimpact.repair.MatchHeader;
import com.goalimpact.repair.PlayerCandidate;
import com.goalimpact.repair.RankedCandidate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The picker's reader against the real snapshot (item 17, slice 1, decision 10).
// CandidateRankerTest pins the ordering rule on plain lists; this pins the half
// that only the vendor's own data can exercise - that the evidence coming out of
// SQL is the evidence the rule expects.
//
// The fixture is derived rather than hand-picked: a game is chosen from the
// snapshot at run time and its own appearance rows become the expected rank 0, so
// a snapshot refresh cannot leave the test quoting a game that moved.
class CandidateQueryTest {

    private static final Path SNAPSHOT = DataFiles.SNAPSHOT;

    @TempDir
    Path tempDir;

    private long gameId;
    private long clubId;
    private LocalDate date;
    private Set<Long> whoPlayed;

    @BeforeEach
    void pickAGameFromTheSnapshot() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");
        try (Connection c = openReadOnly(SNAPSHOT);
            Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("""
                SELECT game_id, player_club_id, min(date) AS date
                FROM appearances GROUP BY game_id, player_club_id
                HAVING count(*) >= 14 ORDER BY game_id LIMIT 1
                """)) {
                assumeTrue(rs.next(), "no appearances in the snapshot");
                gameId = rs.getLong("game_id");
                clubId = rs.getLong("player_club_id");
                date = rs.getDate("date").toLocalDate();
            }
            whoPlayed = new HashSet<>();
            try (ResultSet rs = s.executeQuery("SELECT player_id FROM appearances"
                + " WHERE game_id = " + gameId + " AND player_club_id = " + clubId)) {
                while (rs.next()) {
                    whoPlayed.add(rs.getLong(1));
                }
            }
        }
    }

    private SidecarStore store() {
        return new SidecarStore(tempDir.resolve("sidecar.duckdb"), SNAPSHOT);
    }

    // Rank 0 with nothing typed: everyone who turned out for this club within a
    // month of the date, which is what makes an absent side a pre-ranked list.
    @Test
    void theClubsNearbySquadComesBackWithNothingTyped() throws Exception {
        List<PlayerCandidate> pool = store().candidates(clubId, 0, date, "", 50);

        Set<Long> nearby = new HashSet<>();
        for (PlayerCandidate c : pool) {
            if (c.nearbyMatches() > 0) {
                nearby.add(c.playerId());
            }
        }
        assertTrue(nearby.containsAll(whoPlayed),
            "the men who played the game are not all in its own club's nearby squad");
        assertTrue(pool.stream().allMatch(PlayerCandidate::everPlayedForClub));
    }

    // The match under repair is excluded, so a draft's own rows never rank their
    // own players: without it, re-opening a draft would show everyone in it as a
    // regular on the strength of that one appearance.
    @Test
    void theMatchUnderRepairIsNotItsOwnEvidence() throws Exception {
        List<PlayerCandidate> including = store().candidates(clubId, 0, date, "", 50);
        List<PlayerCandidate> excluding = store().candidates(clubId, gameId, date, "", 50);

        long player = whoPlayed.iterator().next();
        assertEquals(nearbyOf(including, player) - 1, nearbyOf(excluding, player));
    }

    // Rank 2 is asked for only once something is typed (decision 6) - the vendor
    // holds 114,893 players, and an unfiltered list of them is not a picker.
    @Test
    void theEveryoneElseArmIsSilentUntilSomethingIsTyped() throws Exception {
        List<PlayerCandidate> quiet = store().candidates(clubId, 0, date, "", 50);
        assertTrue(quiet.stream().allMatch(PlayerCandidate::everPlayedForClub));

        String someName = anyVendorPlayerName();
        List<PlayerCandidate> typed = store().candidates(clubId, 0, date, someName, 50);
        assertTrue(typed.size() > quiet.size(),
            "typing a name pulled in nothing from the everyone-else arm");
        assertTrue(typed.stream().anyMatch(
            c -> c.playerName() != null
                && c.playerName().toLowerCase().contains(someName.toLowerCase())));
    }

    // Decision 5, the claim the whole slice leans on: a man created on one matchday
    // is a ranked candidate on the next, because the ranking counts sidecar lineups
    // beside vendor appearances. Without it the tool is barely faster than a
    // spreadsheet in exactly the case - a season the vendor does not cover - where
    // it is needed most.
    @Test
    void aPlayerCreatedLastMatchdayRanksOnTheNext() throws Exception {
        SidecarStore store = store();
        EditableMatch first = sidecarMatch(900000101L, date)
            .withManualIdCeiling(store.highestManualPlayerId())
            .create(clubId, "Marc Dupont", "Winger", null, "club programme");
        store.save(first, "released", "p");

        List<PlayerCandidate> pool =
            store.candidates(clubId, 900000102L, date.plusDays(7), "", 50);

        PlayerCandidate dupont = pool.stream()
            .filter(c -> c.playerId() == ManualPlayer.FIRST_ID)
            .findFirst().orElseThrow();
        assertEquals(1, dupont.nearbyMatches());
        assertEquals("Marc Dupont", dupont.playerName());
        assertTrue(dupont.manual());

        List<RankedCandidate> ranked = CandidateRanker.rank(pool, "", Map.of(), 50);
        assertTrue(ranked.stream()
            .anyMatch(r -> r.candidate().playerId() == ManualPlayer.FIRST_ID && r.rank() == 0));
    }

    // ADR 0012 decision 4: the register is authoritative for the name, identity is
    // the id. A released match keeps whatever name it was saved with - it is a
    // whole-match snapshot - so if his lineup rows won the COALESCE, a name
    // corrected in the register could never reach the picker.
    @Test
    void theRegisterNameWinsOverTheNameFrozenIntoASavedMatch() throws Exception {
        SidecarStore store = store();
        EditableMatch match = sidecarMatch(900000101L, date)
            .create(clubId, "Marc Dupond", "Winger", null, "");
        store.save(match, "released", "p");

        try (Connection c = DriverManager.getConnection(
            "jdbc:duckdb:" + tempDir.resolve("sidecar.duckdb"));
            Statement s = c.createStatement()) {
            s.execute("UPDATE manual_players SET player_name = 'Marc Dupont'");
        }

        PlayerCandidate dupont = store.candidates(clubId, 900000102L, date.plusDays(7), "", 50)
            .stream().filter(p -> p.playerId() == ManualPlayer.FIRST_ID)
            .findFirst().orElseThrow();
        assertEquals("Marc Dupont", dupont.playerName());
    }

    // The same man at a club he has never played for: findable only by typing, and
    // only because the register is searched beside the vendor's players table.
    @Test
    void aManualPlayerIsFoundByNameAtAnotherClub() throws Exception {
        SidecarStore store = store();
        EditableMatch first = sidecarMatch(900000101L, date)
            .create(clubId, "Marc Dupont", "Winger", null, "");
        store.save(first, "released", "p");

        long otherClub = clubId + 1;
        assertFalse(store.candidates(otherClub, 0, date, "", 50).stream()
            .anyMatch(c -> c.playerId() == ManualPlayer.FIRST_ID));
        assertTrue(store.candidates(otherClub, 0, date, "dupont", 50).stream()
            .anyMatch(c -> c.playerId() == ManualPlayer.FIRST_ID));
    }

    // A sidecar that pre-dates ADR 0012 has four tables and no register; the picker
    // must read it as "no manual players", not fail to open.
    @Test
    void aSidecarWithNoRegisterStillAnswers() throws Exception {
        Path db = tempDir.resolve("old-sidecar.duckdb");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db);
            Statement s = c.createStatement()) {
            s.execute("CREATE TABLE matches (game_id BIGINT, date DATE)");
            s.execute("CREATE TABLE game_lineups (game_id BIGINT, club_id BIGINT,"
                + " player_id BIGINT, player_name VARCHAR, position VARCHAR)");
        }
        assertFalse(new SidecarStore(db, SNAPSHOT)
            .candidates(clubId, 0, date, "", 50).isEmpty());
    }

    private String anyVendorPlayerName() throws SQLException {
        try (Connection c = openReadOnly(SNAPSHOT);
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery(
                "SELECT name FROM players WHERE name IS NOT NULL ORDER BY player_id LIMIT 1")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int nearbyOf(List<PlayerCandidate> pool, long playerId) {
        return pool.stream().filter(c -> c.playerId() == playerId)
            .findFirst().orElseThrow().nearbyMatches();
    }

    // A minimal lawful sidecar match at this club, so a released row exists for the
    // picker's sidecar arm to count.
    private EditableMatch sidecarMatch(long id, LocalDate on) {
        List<LineupEntry> lineup = new ArrayList<>();
        lineup.add(new LineupEntry(clubId, 800001, "P800001", "Goalkeeper", LineupEntry.STARTER));
        for (int i = 1; i < 10; i++) {
            lineup.add(new LineupEntry(clubId, 800001 + i, "P" + (800001 + i),
                "Centre-Back", LineupEntry.STARTER));
        }
        long away = clubId + 5000;
        lineup.add(new LineupEntry(away, 810001, "P810001", "Goalkeeper", LineupEntry.STARTER));
        for (int i = 1; i < 11; i++) {
            lineup.add(new LineupEntry(away, 810001 + i, "P" + (810001 + i),
                "Centre-Back", LineupEntry.STARTER));
        }
        MatchHeader header = new MatchHeader(id, on, "GRP", "2013", "R1", "domestic_cup",
            clubId, "Home", away, "Away", 0, 0);
        return new EditableMatch(header, lineup, List.of(), List.of(new AppearanceRow(id, 90)));
    }

    private static Connection openReadOnly(Path db) throws SQLException {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + db, readOnly);
    }
}
