package com.goalimpact.data;

import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
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

    public List<MatchEvent> loadEvents(long matchId) throws IOException {
        Path file = dataDir.resolve("events").resolve(matchId + ".json");

        List<MatchEvent> events = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();

                int period = obj.get("period").getAsInt();
                if (period == 5) {
                    continue; // skip penalty shootout
                }
                int minute = obj.get("minute").getAsInt();
                int second = obj.get("second").getAsInt();
                String type = obj.getAsJsonObject("type").get("name").getAsString();

                switch(type) {
                    case "Starting XI" -> {
                        Team team = readTeam(obj);
                        List<Player> players = new ArrayList<>();
                        JsonArray lineup = obj.getAsJsonObject("tactics").getAsJsonArray("lineup");
                        for (JsonElement le : lineup) {
                            JsonObject p = le.getAsJsonObject().getAsJsonObject("player");
                            players.add(readPlayer(p));
                        }
                        events.add(new MatchEvent.StartingXI(period, minute, second, team, players));
                    }
                    case "Substitution" -> {
                        Team team = readTeam(obj);
                        Player off = readPlayer(obj.getAsJsonObject("player"));
                        Player on = readPlayer(obj.getAsJsonObject("substitution")
                                                    .getAsJsonObject("replacement"));
                        events.add(new MatchEvent.Substitution(period, minute, second, team, off, on));
                    }
                    case "Shot" -> {
                        JsonObject shot = obj.getAsJsonObject("shot");
                        String outcome = shot.getAsJsonObject("outcome").get("name").getAsString();
                        if (outcome.equals("Goal")) {
                            Team team = readTeam(obj);
                            Player scorer = readPlayer(obj.getAsJsonObject("player"));
                            events.add(new MatchEvent.Goal(period, minute, second, team, scorer));
                        }
                    }
                    case "Own Goal For" -> {
                        Team team = readTeam(obj);
                        events.add(new MatchEvent.Goal(period, minute, second, team, null));
                    }
                    case "Bad Behaviour" -> {
                        JsonObject bb = obj.getAsJsonObject("bad_behaviour");
                        if (bb.has("card")) {
                            String card = bb.getAsJsonObject("card").get("name").getAsString();
                            if (card.equals("Red Card") || card.equals("Second Yellow")) {
                                events.add(new MatchEvent.RedCard(period, minute, second,
                                            readTeam(obj), readPlayer(obj.getAsJsonObject("player"))));
                            }
                        }
                    }
                    case "Foul Committed" -> {
                        if (obj.has("foul_committed")) {
                            JsonObject fc = obj.getAsJsonObject("foul_committed");
                            if (fc.has("card")) {
                                String card = fc.getAsJsonObject("card").get("name").getAsString();
                                if (card.equals("Red Card") || card.equals("Second Yellow")) {
                                    events.add(new MatchEvent.RedCard(period, minute, second,
                                                readTeam(obj), readPlayer(obj.getAsJsonObject("player"))));
                                }
                            }
                        }
                    }
                    default -> {
                        // all other event types are irrelevant to GoalImpact - ignore
                    }
                }
            }
        }
        return events;
    }

    private Team readTeam(JsonObject event) {
        JsonObject t = event.getAsJsonObject("team");
        return new Team(t.get("id").getAsLong(), t.get("name").getAsString());
    }

    private Player readPlayer(JsonObject playerObj) {
        return new Player(playerObj.get("id").getAsLong(), playerObj.get("name").getAsString());
    }
}
