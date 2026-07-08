package com.goalimpact.data;

import com.goalimpact.model.Match;
import com.goalimpact.model.Team;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DataLoader {
    
    private final Path dataDir;

    public DataLoader(Path dataDir) {
        this.dataDir = dataDir;
    }

    public List<Match> loadMatches(int competitionId, int seasonId) throws IOException {
        Path file = dataDir.resolve("matches")
                            .resolve(String.valueOf(competitionId))
                            .resolve(seasonId + ".json");

        List<Match> matches = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();

                long matchId = obj.get("match_id").getAsLong();

                JsonObject homeObj = obj.getAsJsonObject("home_team");
                Team home = new Team(
                    homeObj.get("home_team_id").getAsLong(),
                    homeObj.get("home_team_name").getAsString());

                JsonObject awayObj = obj.getAsJsonObject("away_team");
                Team away = new Team(
                    awayObj.get("away_team_id").getAsLong(),
                    awayObj.get("away_team_name").getAsString());

                int homeScore = obj.get("home_score").getAsInt();
                int awayScore = obj.get("away_score").getAsInt();

                matches.add(new Match(matchId, home, away, homeScore, awayScore));
            }
        }
        return matches;
    }

}
