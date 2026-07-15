package com.goalimpact.data;

import com.goalimpact.model.CompetitionSeason;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DataLoader {
    
    private final Path dataDir;

    // country_name values StatsBomb gives cross-border competitions; any
    // real country name means a domestic competition (ADR 0008).
    private static final Set<String> REGIONS = Set.of("Africa", "Asia",
        "Europe", "International", "North and Central America", "Oceania",
        "South America");

    // Domestic stages that are a single match at a chosen neutral ground,
    // never anyone's home fixture (cup finals; league stages never use these).
    private static final Set<String> SINGLE_MATCH_FINALS =
        Set.of("Final", "Championship - Final");

    public DataLoader(Path dataDir) {
        this.dataDir = dataDir;
    }

    public List<CompetitionSeason> loadCompetitions() throws IOException {
        Path file = dataDir.resolve("competitions.json");

        List<CompetitionSeason> competitions = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                competitions.add(new CompetitionSeason(
                    obj.get("competition_id").getAsInt(),
                    obj.get("season_id").getAsInt(),
                    obj.get("competition_name").getAsString(),
                    obj.get("season_name").getAsString(),
                    obj.get("competition_gender").getAsString(),
                    obj.get("country_name").getAsString()));
            }
        }
        return competitions;
    }

    public List<Match> loadMatches(CompetitionSeason competition) throws IOException {
        Path file = dataDir.resolve("matches")
                    .resolve(String.valueOf(competition.competitionId()))
                    .resolve(competition.seasonId() + ".json");
        List<Match> matches = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();

                long matchId = obj.get("match_id").getAsLong();
                LocalDate date = LocalDate.parse(obj.get("match_date").getAsString());

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

                matches.add(new Match(matchId, date, home, away, homeScore, awayScore,
                    classifyHomeSide(competition, obj)));
            }
        }
        return matches;
    }

    // ADR 0008: the two-world rule. Domestic competitions trust the fixture
    // label; cross-border competitions trust geography - a side is at home
    // if the stadium stands in its country.
    private Match.HomeSide classifyHomeSide(CompetitionSeason competition, JsonObject match) {
        if (REGIONS.contains(competition.countryName())) {
            String stadiumCountry = stadiumCountryOf(match);
            if (stadiumCountry == null) {
                return Match.HomeSide.NEITHER;  // no evidence, no advantage
            }
            boolean homeInOwnCountry = stadiumCountry.equals(countryOf(match, "home_team"));
            boolean awayInOwnCountry = stadiumCountry.equals(countryOf(match, "away_team"));
            if (homeInOwnCountry == awayInOwnCountry) {
                // Neither side's country - or both sides' (a same-country
                // tie): geography cannot pick a host, so nobody is one.
                return Match.HomeSide.NEITHER;
            }
            return homeInOwnCountry ? Match.HomeSide.HOME : Match.HomeSide.AWAY;
        }
        if (SINGLE_MATCH_FINALS.contains(stageOf(match))) {
            return Match.HomeSide.NEITHER;  // cup final at a neutral ground
        }
        if (competition.competitionId() == 1238 && competition.seasonId() == 108) {
            // ISL 2021/22: the whole season in a COVID bubble - 115 matches
            // in 3 stadiums, no travel, every home label a fiction
            return Match.HomeSide.NEITHER;
        }
        return Match.HomeSide.HOME;
    }

    // The open dataset occasionally lists a match whose events file is not
    // shipped. Callers use this to skip those instead of crashing mid-replay.
    public boolean hasEvents(long matchId) {
        return Files.exists(dataDir.resolve("events").resolve(matchId + ".json"));
    }

    public List<MatchEvent> loadEvents(Match match) throws IOException {
        long matchId = match.matchId();
        Path file = dataDir.resolve("events").resolve(matchId + ".json");

        long homeTeamId = switch (match.homeSide()) {
            case HOME -> match.home().id();
            case AWAY -> match.away().id();
            case NEITHER -> -1;
        };
        int homeFlagged = 0;

        List<MatchEvent> events = new ArrayList<>();
        // Translate StatsBomb's restarting per-period clocks onto one
        // continuous playing clock: seconds of actual play since kickoff.
        int offsetSeconds = 0;  // summed true lengths of the periods already ended
        int endedPeriod = 0;    // highest period whose Half End we have seen.
        int maxT = 0;
        int periodMaxRaw = 0;   // latest raw stamp seen inside the running period
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();

                int period = obj.get("period").getAsInt();
                if (period == 5) {
                    continue; // skip penalty shootout
                }
                int rawSeconds = obj.get("minute").getAsInt() * 60
                                + obj.get("second").getAsInt();
                String type = obj.getAsJsonObject("type").get("name").getAsString();

                if (type.equals("Half End")) {
                    if (period > endedPeriod) {   // arrives once per team - count each period once
                        // The Half End stamp can land a second before the last
                        // in-play event's stamp - the period end at whichever
                        // is latest.
                        offsetSeconds += Math.max(rawSeconds, periodMaxRaw) - nominalStart(period);
                        endedPeriod = period;
                        periodMaxRaw = 0;
                    }
                    continue;
                }
                int t;
                if (period == endedPeriod + 1) {
                    // normal case: an event inside the running period
                    periodMaxRaw = Math.max(periodMaxRaw, rawSeconds);
                    t = offsetSeconds + (rawSeconds - nominalStart(period));
                } else if (period == endedPeriod) {
                    // trailing event of a period that already ended, e.g. a red
                    // card shown after the whistle: it happened outside play, so
                    // it lands at the playing clock's current end - the state
                    // effect applies, but zero playing time is attached.
                    t = offsetSeconds;
                } else {
                    throw new IllegalStateException("match " + matchId + ": period " + period
                        + "events arrived while period " + endedPeriod
                        + " was the last one ended - the clock would lie");
                }
                int minute = t / 60;
                int second = t % 60;
                maxT = Math.max(maxT, t);

                switch(type) {
                    case "Starting XI" -> {
                        Team team = readTeam(obj);
                        List<Player> players = new ArrayList<>();
                        Player goalkeeper = null;
                        JsonArray lineup = obj.getAsJsonObject("tactics").getAsJsonArray("lineup");
                        for (JsonElement le : lineup) {
                            JsonObject entry = le.getAsJsonObject();
                            Player player = readPlayer(entry.getAsJsonObject("player"));
                            players.add(player);
                            if (entry.getAsJsonObject("position").get("id").getAsInt() == 1) {
                                goalkeeper = player;
                            }
                        }
                        if (goalkeeper == null) {
                            throw new IllegalStateException(
                                "Starting XI without a goalkeeper: match " + matchId + ", team " + team.name());
                        }
                        boolean home = team.id() == homeTeamId;
                        if (home) {
                            homeFlagged++;
                        }
                        events.add(new MatchEvent.StartingXI(period, minute, second,
                            team, players, goalkeeper, home));
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
        if (endedPeriod == 0) {
            throw new IllegalStateException("match " + matchId
                + ": no Half End events - match length unknown.");
        }
        if (offsetSeconds < maxT) {
            throw new IllegalStateException("match " + matchId + ": whistle at "
                + offsetSeconds + "s but an event happened at " + maxT
                + "s - the continuous clock is broken.");
        }
        if (match.homeSide() != Match.HomeSide.NEITHER && homeFlagged != 1) {
            throw new IllegalStateException("match " + matchId + ": home side is "
                + match.homeSide() + " but " + homeFlagged
                + " lineups were flagged home - matches and events disagree on team ids");
        }
        events.add(new MatchEvent.MatchEnd(endedPeriod, offsetSeconds / 60, offsetSeconds % 60));
        return events;
    }

    // StatsBomb clocks restart each period: the second half begins at 45:00
    // even when first-half stoppage ran past it, extra time at 90:00 and
    // 105:00 (verified against the Half Start events in the open data).
    private static int nominalStart(int period) {
        return switch(period) {
            case 1 -> 0;
            case 2 -> 45 * 60;
            case 3 -> 90 * 60;
            case 4 -> 105 * 60;
            default -> throw new IllegalStateException("unexpected period " + period);
        };
    }

    private Team readTeam(JsonObject event) {
        JsonObject t = event.getAsJsonObject("team");
        return new Team(t.get("id").getAsLong(), t.get("name").getAsString());
    }

    private Player readPlayer(JsonObject playerObj) {
        return new Player(playerObj.get("id").getAsLong(), playerObj.get("name").getAsString());
    }

    private static String stageOf(JsonObject match) {
        return match.getAsJsonObject("competition_stage").get("name").getAsString();
    }

    private static String countryOf(JsonObject match, String team) {
        return match.getAsJsonObject(team).getAsJsonObject("country").get("name").getAsString();
    }

    // A handful of old matches ship without a stadium; null means "unknown".
    private static String stadiumCountryOf(JsonObject match) {
        if (!match.has("stadium") || !match.get("stadium").isJsonObject()) {
            return null;
        }
        return match.getAsJsonObject("stadium").getAsJsonObject("country").get("name").getAsString();
    }
}
