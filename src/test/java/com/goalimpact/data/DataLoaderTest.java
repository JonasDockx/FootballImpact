package com.goalimpact.data;

import com.goalimpact.model.CompetitionSeason;
import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Team;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataLoaderTest {

    // A miniature copy of the StatsBomb data layout, checked into the repo,
    // so this test never depends on the real dataset's location or contents.
    private final DataLoader loader =
        new DataLoader(Path.of("src", "test", "resources", "statsbomb-fixture"));

    // loadMatches takes the CompetitionSeason record directly, so these
    // fixture handles never need to appear in the fixture competitions.json.
    private static final CompetitionSeason EURO_2024 =
        new CompetitionSeason(55, 282, "UEFA Euro", "2024", "male", "Europe");
    private static final CompetitionSeason CL_2018_19 =
        new CompetitionSeason(16, 4, "Champions League", "2018/2019", "male", "Europe");
    private static final CompetitionSeason COPA_DEL_REY_1982_83 =
        new CompetitionSeason(87, 268, "Copa del Rey", "1982/1983", "male", "Spain");
    private static final CompetitionSeason LA_LIGA_2015_16 =
        new CompetitionSeason(11, 27, "La Liga", "2015/2016", "male", "Spain");
    private static final CompetitionSeason ISL_2021_22 =
        new CompetitionSeason(1238, 108, "Indian Super league", "2021/2022", "male", "India");

    private Match match(CompetitionSeason competition, long matchId) throws IOException {
        return loader.loadMatches(competition).stream()
            .filter(m -> m.matchId() == matchId)
            .findFirst().orElseThrow();
    }

    private static Match eventsFixtureMatch(Match.HomeSide homeSide) {
        return new Match(9001, LocalDate.of(2024, 1, 1),
            new Team(100, "Alpha FC"), new Team(200, "Beta United"), 0, 0, homeSide);
    }

    @Test
    void loadsEveryCompetitionSeasonPair() throws IOException {
        List<CompetitionSeason> competitions = loader.loadCompetitions();

        assertEquals(3, competitions.size());
    }

    @Test
    void parsesTheFieldsOfACompetitionSeason() throws IOException {
        CompetitionSeason euro = loader.loadCompetitions().get(0);

        assertEquals(55, euro.competitionId());
        assertEquals(282, euro.seasonId());
        assertEquals("UEFA Euro", euro.competitionName());
        assertEquals("2024", euro.seasonName());
        assertEquals("male", euro.gender());
        assertEquals("Europe", euro.countryName());
    }

    @Test
    void carriesGenderSoCallersCanFilter() throws IOException {
        List<CompetitionSeason> competitions = loader.loadCompetitions();

        long female = competitions.stream()
            .filter(c -> c.gender().equals("female"))
            .count();

        assertEquals(1, female);
    }

        @Test
    void crossBorderMatchOnNeutralGroundHasNoHomeSide() throws IOException {
        // Portugal vs Czechia in Leipzig: the label is administrative.
        assertEquals(Match.HomeSide.NEITHER, match(EURO_2024, 9101).homeSide());
    }

    @Test
    void tournamentHostLabeledHomeIsTheHomeSide() throws IOException {
        // Germany at the Allianz Arena.
        assertEquals(Match.HomeSide.HOME, match(EURO_2024, 9102).homeSide());
    }

    @Test
    void tournamentHostLabeledAwayIsStillTheHomeSide() throws IOException {
        // Hungary "hosts" Germany in Stuttgart: geography beats the label.
        assertEquals(Match.HomeSide.AWAY, match(EURO_2024, 9103).homeSide());
    }

    @Test
    void missingStadiumMeansNoEvidenceAndNoHomeSide() throws IOException {
        assertEquals(Match.HomeSide.NEITHER, match(EURO_2024, 9104).homeSide());
    }

    @Test
    void sameCountryTieDefeatsGeography() throws IOException {
        // Real vs Atletico in Spain: both countries match the stadium's, so
        // geography cannot pick a host - the documented ADR 0008 limitation.
        assertEquals(Match.HomeSide.NEITHER, match(CL_2018_19, 9201).homeSide());
    }

    @Test
    void domesticLeagueTrustsTheLabel() throws IOException {
        assertEquals(Match.HomeSide.HOME, match(LA_LIGA_2015_16, 9401).homeSide());
    }

    @Test
    void domesticCupFinalIsNeutralGround() throws IOException {
        // The 1983 Copa del Rey final at La Romareda.
        assertEquals(Match.HomeSide.NEITHER, match(COPA_DEL_REY_1982_83, 9301).homeSide());
    }

    @Test
    void islBubbleSeasonIsPinnedAllNeutral() throws IOException {
        // 115 matches in 3 stadiums: every home label that season is fiction.
        assertEquals(Match.HomeSide.NEITHER, match(ISL_2021_22, 9501).homeSide());
    }

    @Test
    void homeFlagLandsOnTheHomeSidesStartingXI() throws IOException {
        List<MatchEvent> events = loader.loadEvents(eventsFixtureMatch(Match.HomeSide.AWAY));

        MatchEvent.StartingXI alpha = (MatchEvent.StartingXI) events.get(0);
        MatchEvent.StartingXI beta = (MatchEvent.StartingXI) events.get(1);
        assertFalse(alpha.home());
        assertTrue(beta.home()); // the labeled away team is the home side here
    }

    @Test
    void neutralVenueFlagsNobody() throws IOException {
        List<MatchEvent> events = loader.loadEvents(eventsFixtureMatch(Match.HomeSide.NEITHER));

        assertFalse(((MatchEvent.StartingXI) events.get(0)).home());
        assertFalse(((MatchEvent.StartingXI) events.get(1)).home());
    }

    @Test
    void tripwireFiresWhenMatchesAndEventsDisagreeOnTeamIds() {
        Match ghost = new Match(9001, LocalDate.of(2024, 1, 1),
            new Team(999, "Ghost"), new Team(998, "Phantom"), 0, 0, Match.HomeSide.HOME);

        assertThrows(IllegalStateException.class, () -> loader.loadEvents(ghost));
    }
}
