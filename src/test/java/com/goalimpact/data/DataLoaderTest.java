package com.goalimpact.data;

import com.goalimpact.model.CompetitionSeason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataLoaderTest {

    // A miniature copy of the StatsBomb data layout, checked into the repo,
    // so this test never depends on the real dataset's location or contents.
    private final DataLoader loader =
        new DataLoader(Path.of("src", "test", "resources", "statsbomb-fixture"));

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
    }

    @Test
    void carriesGenderSoCallersCanFilter() throws IOException {
        List<CompetitionSeason> competitions = loader.loadCompetitions();

        long female = competitions.stream()
            .filter(c -> c.gender().equals("female"))
            .count();

        assertEquals(1, female);
    }
}
