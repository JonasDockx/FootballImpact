package com.goalimpact.data;

import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TransfermarktLoaderTest {

    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // The home-side rule needs no database: it reads two strings the
    // loader already pulled out of the row.

    @Test
    void clubLeagueMatchTrustsTheLabel() {
        assertEquals(Match.HomeSide.HOME,
            TransfermarktLoader.classifyHomeSide("domestic_league", "12. Matchday"));
    }

    @Test
    void oneOffFinalIsNobodysHomeFixture() {
        assertEquals(Match.HomeSide.NEITHER,
            TransfermarktLoader.classifyHomeSide("domestic_cup", "Final"));
    }

    @Test
    void twoLeggedFinalKeepsItsHomeLeg() {
        assertEquals(Match.HomeSide.HOME,
            TransfermarktLoader.classifyHomeSide("international_cup", "final 1st leg"));
    }

    @Test
    void nationalTeamCompetitionRefusesToGuess() {
        assertThrows(IllegalStateException.class,
            () -> TransfermarktLoader.classifyHomeSide("national_team_competition", "Final"));
    }

    // The slice itself needs the 195 MiB vendor snapshot, so it skips
    // where that is absent - mvn test stays runnable with no database.

    @Test
    void loadsThePremierLeague2024Slice() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<Match> matches = loader.loadMatches("GB1", "2024");

            assertEquals(380, matches.size());
            assertEquals(LocalDate.of(2024, 8, 16), matches.get(0).date());
            assertEquals(LocalDate.of(2025, 5, 25), matches.get(matches.size() - 1).date());
            assertTrue(matches.stream().allMatch(m -> m.homeSide() == Match.HomeSide.HOME));
        }
    }

    @Test
    void readsTheFirstFixtureWholeAndUnswapped() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            Match opener = loader.loadMatches("GB1", "2024").get(0);

            assertEquals(4361261L, opener.matchId());
            assertEquals("Manchester United", opener.home().name());
            assertEquals("Fulham FC", opener.away().name());
            assertEquals(1, opener.homeScore());
            assertEquals(0, opener.awayScore());
        }
    }

    
    private static Match opener(TransfermarktLoader loader) throws Exception {
        return loader.loadMatches("GB1", "2024").get(0);
    }

    @Test
    void bothStartingElevensArriveWithTheirGoalkeeper() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<MatchEvent> events = loader.loadEvents(opener(loader));

            MatchEvent.StartingXI united = (MatchEvent.StartingXI) events.get(0);
            MatchEvent.StartingXI fulham = (MatchEvent.StartingXI) events.get(1);
            assertEquals(11, united.players().size());
            assertEquals(11, fulham.players().size());
            assertEquals(234509L, united.goalkeeper().id());   // Onana
            assertEquals(72476L, fulham.goalkeeper().id());    // Leno
            assertTrue(united.players().contains(united.goalkeeper()));
        }
    }

    @Test
    void theHomeFlagLandsOnTheHomeSideOnly() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<MatchEvent> events = loader.loadEvents(opener(loader));

            assertTrue(((MatchEvent.StartingXI) events.get(0)).home());
            assertFalse(((MatchEvent.StartingXI) events.get(1)).home());
        }
    }

    @Test
    void theWhistleIsNominalNinety() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<MatchEvent> events = loader.loadEvents(opener(loader));

            MatchEvent.MatchEnd end = (MatchEvent.MatchEnd) events.get(events.size() - 1);
            assertEquals(90, end.minute());
            assertEquals(0, end.second());
        }
    }

    @Test
    void everyMatchInTheSliceIsUsable() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            for (Match match : loader.loadMatches("GB1", "2024")) {
                loader.loadEvents(match);   // throws if the gate rejects it
            }
        }
    }

    
    @Test
    void seasonCounterIsNotASecondYellow() {
        assertFalse(TransfermarktLoader.isSendingOff("3. Yellow card  , Foul"));
        assertFalse(TransfermarktLoader.isSendingOff("1. Yellow card  , Diving"));
    }

    @Test
    void redsAndSecondYellowsEndTheMatch() {
        assertTrue(TransfermarktLoader.isSendingOff("Red card , Foul"));
        assertTrue(TransfermarktLoader.isSendingOff("Second yellow , Foul"));
        assertTrue(TransfermarktLoader.isSendingOff("2. Red card , Foul"));
    }

    @Test
    void ownGoalsAreRecognisedFromTheDescription() {
        assertTrue(TransfermarktLoader.isOwnGoal(", Own-goal Assist: Without assist"));
        assertFalse(TransfermarktLoader.isOwnGoal(", Left-footed shot, 1. Goal of the Season"));
        assertFalse(TransfermarktLoader.isOwnGoal(null));
    }

    @Test
    void replaysTheOpenerEventByEvent() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<MatchEvent> events = loader.loadEvents(opener(loader));

            // Two XIs, ten substitutions, Zirkzee's 87th-minute winner,
            // three yellows that are not events, and the whistle.
            assertEquals(14, events.size());
            MatchEvent.Goal goal = (MatchEvent.Goal) events.stream()
                .filter(e -> e instanceof MatchEvent.Goal).findFirst().orElseThrow();
            assertEquals(435648L, goal.scorer().id());        // Zirkzee
            assertEquals(985L, goal.scoringTeam().id());      // Manchester United
            assertEquals(86, goal.minute());                  // minute 87, at its midpoint
            assertEquals(30, goal.second());
            assertEquals(2, goal.period());
        }
    }

    @Test
    void theSliceAddsUpToWhatTheSourceClaims() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            int goals = 0;
            int substitutions = 0;
            int redCards = 0;
            for (Match match : loader.loadMatches("GB1", "2024")) {
                for (MatchEvent event : loader.loadEvents(match)) {
                    if (event instanceof MatchEvent.Goal) {
                        goals++;
                    } else if (event instanceof MatchEvent.Substitution) {
                        substitutions++;
                    } else if (event instanceof MatchEvent.RedCard) {
                        redCards++;
                    }
                }
            }
            assertEquals(1115, goals);
            assertEquals(3207, substitutions);
            assertEquals(52, redCards);        // 26 reds + 26 second yellows
            assertEquals(0, loader.droppedEvents());
        }
    }
}
