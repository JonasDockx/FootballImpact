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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TransfermarktLoaderTest {

    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // The home-side rule needs no database: it reads two strings the
    // loader already pulled out of the row.

    private static Match.HomeSide verdict(String competitionId, String season,
        String competitionType, String round, long homeClubId, long awayClubId) {
        return TransfermarktLoader.classifyHomeSide(new TransfermarktLoader.Fixture(
            competitionId, season, competitionType, round, homeClubId, awayClubId));
    }

    @Test
    void clubLeagueMatchTrustsTheLabel() {
        assertEquals(Match.HomeSide.HOME,
            verdict("GB1", "2024", "domestic_league", "12. Matchday", 985, 931));
    }

    @Test
    void oneOffFinalIsNobodysHomeFixture() {
        assertEquals(Match.HomeSide.NEITHER,
            verdict("FAC", "2024", "domestic_cup", "Final", 1, 2));
    }

    @Test
    void twoLeggedFinalKeepsItsHomeLeg() {
        assertEquals(Match.HomeSide.HOME,
            verdict("CL", "2024", "international_cup", "final 1st leg", 1, 2));
    }

    @Test
    void faCupSemiFinalsArePlayedAtWembley() {
        assertEquals(Match.HomeSide.NEITHER,
            verdict("FAC", "2024", "domestic_cup", "Semi-Finals", 1, 2));
    }

    @Test
    void otherCupsKeepTheirSemiFinalHomeSide() {
        // The DFB-Pokal semi is played at one club's own ground.
        assertEquals(Match.HomeSide.HOME,
            verdict("DFB", "2024", "domestic_cup", "Semi-Finals", 1, 2));
    }

    @Test
    void tournamentHostLabelledHomeIsAtHome() {
        // Qatar v Lebanon, the opening match in Doha.
        assertEquals(Match.HomeSide.HOME,
            verdict("AFAC", "2024", "national_team_competition", "Group A", 14162, 1));
    }

    @Test
    void tournamentHostLabelledAwayIsStillAtHome() {
        // The Asian Cup final: Jordan labelled home, Qatar labelled away,
        // played at Lusail. The label is administrative; the host is real.
        assertEquals(Match.HomeSide.AWAY,
            verdict("AFAC", "2024", "national_team_competition", "Final", 1, 14162));
    }

    @Test
    void tournamentMatchWithoutTheHostIsNeutral() {
        assertEquals(Match.HomeSide.NEITHER,
            verdict("AFAC", "2024", "national_team_competition", "Group B", 1, 2));
    }

    @Test
    void uncuratedEditionGrantsNobodyAnAdvantage() {
        // World Cup 2026 has three hosts, which the table cannot express.
        assertEquals(Match.HomeSide.NEITHER,
            verdict("FIWC", "2025", "national_team_competition", "Group A", 1, 2));
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
    
    private static Match match(TransfermarktLoader loader,
        String competitionId, String season, long gameId) throws Exception {
        return loader.loadMatches(competitionId, season).stream()
            .filter(m -> m.matchId() == gameId)
            .findFirst().orElseThrow();
    }

    private static MatchEvent.MatchEnd whistleOf(List<MatchEvent> events) {
        return (MatchEvent.MatchEnd) events.get(events.size() - 1);
    }

    @Test
    void extraTimeIsSeenFromTheEventsWhereAppearancesIsSilent() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            // Asian Cup quarter-final, Qatar v Uzbekistan: events to minute
            // 114, and not one appearance row anywhere in the competition.
            List<MatchEvent> events = loader.loadEvents(match(loader, "AFAC", "2024", 4280472L));

            assertEquals(120, whistleOf(events).minute());
            assertEquals(4, whistleOf(events).period());
        }
    }

    @Test
    void extraTimeIsSeenFromAppearancesWhereTheEventsAreSilent() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            // Forest v Bristol City: a quiet extra time - the last event
            // is in minute 90 and the match still lasted 120.
            List<MatchEvent> events = loader.loadEvents(match(loader, "FAC", "2023", 4279371L));

            assertEquals(120, whistleOf(events).minute());
        }
    }

    @Test
    void theWhistleIsAlwaysTheLastEvent() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            for (Match m : loader.loadMatches("FAC", "2024")) {
                List<MatchEvent> events = loader.loadEvents(m);
                MatchEvent.MatchEnd end = whistleOf(events);
                assertEquals(events.size() - 1, events.indexOf(end));
            }
        }
    }

    @Test
    void theFaCupSliceIsFullOfExtraTime() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "vendor snapshot not present");

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            int extraTime = 0;
            for (Match m : loader.loadMatches("FAC", "2024")) {
                if (whistleOf(loader.loadEvents(m)).minute() == 120) {
                    extraTime++;
                }
            }
            // 33 of 123 ties go to extra time - replays were abolished for
            // 2024/25. Neither signal alone finds them: appearances knows
            // about 16, and the built events about 32. The 33rd is Stoke
            // 3-3 Cardiff on 2025-02-08, which went to a shootout and so
            // must have played 120 - but its only trace past minute 90 is
            // a yellow card at 98', which never becomes an event, while
            // appearances claims the longest stint was 87 minutes.
            assertEquals(33, extraTime);


        }
    }
    
    @Test
    void theClubWorldCupIsNeverAnyonesHomeFixture() {
        // A tournament at a chosen host, and the host is not resolvable:
        // most entrants play in leagues this snapshot does not carry.
        // 148 replayable matches, every one of them neutral.
        assertEquals(Match.HomeSide.NEITHER,
            verdict("KLUB", "2025", null, "Group A", 1, 2));
        assertEquals(Match.HomeSide.NEITHER,
            verdict("KLUB", "2016", null, "First Round", 1, 2));
    }

    @Test
    void theUkrainianSuperCupIsNeutralWhateverTheRoundIsCalled() {
        // Nine of its ten rows are named "Final" and were already neutral
        // through the finals rule; the tenth is "final decider" and was
        // not. The fact is about the competition, so it is scoped to one.
        assertEquals(Match.HomeSide.NEITHER,
            verdict("UKRS", "2020", null, "final decider", 1, 2));
        assertEquals(Match.HomeSide.NEITHER,
            verdict("UKRS", "2015", null, "Final", 1, 2));
    }

    @Test
    void saudiHostedSuperCupSemiFinalsAreNeutral() {
        assertEquals(Match.HomeSide.NEITHER,
            verdict("SUC", "2024", "other", "Semi-Finals", 1, 2));
        assertEquals(Match.HomeSide.NEITHER,
            verdict("SCI", "2024", "other", "Semi-Finals", 1, 2));
    }

    @Test
    void theTwoLeggedSupercopaIsStillAGenuineHomeAndAway() {
        // Pre-2020 the Supercopa was two legs at the clubs' own grounds.
        // The competition changed shape; the data records both, and an
        // exact match on "Final" leaves these alone.
        assertEquals(Match.HomeSide.HOME,
            verdict("SUC", "2016", "other", "final 1st leg", 1, 2));
        assertEquals(Match.HomeSide.HOME,
            verdict("SUC", "2016", "other", "final 2nd leg", 1, 2));
    }
}
