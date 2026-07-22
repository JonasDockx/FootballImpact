package com.goalimpact.engine;

import com.goalimpact.credit.Lineup;
import com.goalimpact.credit.ResidualSource;
import com.goalimpact.credit.RatingLookup;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MatchProcessor {
    
    private final ResidualSource residualSource;
    private final UpdateSchedule schedule;

    public MatchProcessor(ResidualSource residualSource, UpdateSchedule schedule) {
        this.residualSource = residualSource;
        this.schedule = schedule;
    }

    // The two-argument form is the contract every existing caller has, and it
    // keeps it: no history, no observer, no change. ADR 0011's seam is the
    // overload below.
    public void process(List<MatchEvent> events, Map<Long, PlayerTally> tallies) {
        process(events, tallies, MatchObserver.NONE);
    }

    public void process(List<MatchEvent> events, Map<Long, PlayerTally> tallies,
        MatchObserver observer) {
        // Rating period: freeze every player's rating AND exposure at their
        // pre-match values. Every goal is judged against the frozen ratings,
        // every update sized by the frozen exposure; updates apply only at
        // the final whistle.
        //
        // Only this match's own participants are frozen, never the whole
        // population: preMatch is read only through Lineup, and frozenMinutes
        // only for ids in matchResiduals - both of which hold on-pitch
        // players, so freezing anyone else produces a value nothing reads.
        // Freezing all of tallies costs matches x players: ~1.6M map writes
        // over one season, ~8,9 billion over the full spine - per replay,
        // per grid cell
        Map<Long, Double> frozen = new HashMap<>();
        Map<Long, Double> frozenMinutes = new HashMap<>();
        for (MatchEvent e : events) {
            switch(e) {
                case MatchEvent.StartingXI s -> {
                    for (Player p : s.players()) {
                        freeze(p.id(), tallies, frozen, frozenMinutes);
                    }
                }
                case MatchEvent.Substitution sub ->
                    freeze(sub.playerOn().id(), tallies, frozen, frozenMinutes);
                default -> { }
            }
        }
        RatingLookup preMatch = id -> frozen.getOrDefault(id, 0.0);

        Map<Long, Set<Player>> onPitch = new HashMap<>(); // teamId -> players currently on
        Map<Long, Integer> enterTime = new HashMap<>(); // playerId -> stint start (seconds)
        Map<Long, Double> matchResiduals = new HashMap<>(); // playerId -> summed residuals
        Map<Long, Integer> playedSeconds = new HashMap<>(); // playerId -> on-pitch seconds THIS match


        int lastTime = 0;
        int segStart = 0;   // when the current lineup-constant segment began
        long homeTeamId = -1;   // -1: no home side (neutral venue)

        for (MatchEvent e : events) {
            int t = e.minute() * 60 + e.second();
            lastTime = t;

            switch(e) {
                case MatchEvent.StartingXI s -> {
                    Set<Player> set = onPitch.computeIfAbsent(s.team().id(), k -> new HashSet<>());
                    for (Player p : s.players()) {
                        set.add(p);
                        enterTime.put(p.id(), t);
                        tallies.computeIfAbsent(p.id(), k -> new PlayerTally(p, s.team()))
                            .playsFor(s.team());
                    }
                    tallies.get(s.goalkeeper().id()).startedInGoal();
                    if (s.home()) {
                        homeTeamId = s.team().id();
                    }
                }
                case MatchEvent.Substitution sub -> {
                    closeSegment(onPitch, homeTeamId, preMatch, tallies, matchResiduals, segStart, t);
                    segStart = t;
                    onPitch.get(sub.team().id()).remove(sub.playerOff());
                    leavePitch(tallies, enterTime, playedSeconds, sub.playerOff(), sub.team(), t);


                    onPitch.get(sub.team().id()).add(sub.playerOn());
                    enterTime.put(sub.playerOn().id(), t);
                    tallies.computeIfAbsent(sub.playerOn().id(), k -> new PlayerTally(sub.playerOn(), sub.team()))
                        .playsFor(sub.team());
                }
                case MatchEvent.RedCard rc -> {
                    closeSegment(onPitch, homeTeamId, preMatch, tallies, matchResiduals, segStart, t);
                    segStart = t;
                    onPitch.get(rc.team().id()).remove(rc.player());
                    leavePitch(tallies, enterTime, playedSeconds, rc.player(), rc.team(), t);
                }
                case MatchEvent.Goal g -> {
                    long scoringId = g.scoringTeam().id();
                    Set<Player> scoringOnPitch = onPitch.getOrDefault(scoringId, Set.of());

                    Set<Player> concedingOnPitch = new HashSet<>();
                    for (Map.Entry<Long, Set<Player>> entry : onPitch.entrySet()) {
                        if (entry.getKey() != scoringId) {
                            concedingOnPitch.addAll(entry.getValue());
                        }
                    }
                    
                    boolean scoringHome = scoringId == homeTeamId;
                    boolean concedingHome = homeTeamId != -1 && !scoringHome;
                    Map<Player, Double> deltas = residualSource.goal(
                        new Lineup(scoringOnPitch, scoringHome, goalkeepers(scoringOnPitch, tallies)),
                        new Lineup(concedingOnPitch, concedingHome, goalkeepers(concedingOnPitch, tallies)), preMatch);
                    for (Map.Entry<Player, Double> d : deltas.entrySet()) {
                        matchResiduals.merge(d.getKey().id(), d.getValue(), Double::sum);
                    }
                }
                case MatchEvent.MatchEnd end -> {
                    // The whistle closes the final segment; its timestamp already
                    // became lastTime, which closes every open stint.
                    closeSegment(onPitch, homeTeamId, preMatch, tallies, matchResiduals, segStart, t);
                }
            }
        }

        // Final whistle: apply one rating update per player...
        for(Map.Entry<Long, Double> entry : matchResiduals.entrySet()) {
            double k = schedule.factor(frozenMinutes.getOrDefault(entry.getKey(), 0.0));
            tallies.get(entry.getKey()).applyUpdate(k * entry.getValue());
        }
        // ...and close out on-pitch time for everyone still on the pitch.
        for (Map.Entry<Long, Integer> entry : enterTime.entrySet()) {
            tallies.get(entry.getKey()).addSeconds(lastTime - entry.getValue());
            playedSeconds.merge(entry.getKey(), lastTime - entry.getValue(), Integer::sum);
        }
        // ADR 0011: the match's per-player story, told after both loops so the
        // tally already carries the post-match rating. playedSeconds holds
        // everyone who was on the pitch and nobody else - a player either
        // leaves through leavePitch or is closed out by the loop above, and
        // both record here.
        for (Map.Entry<Long, Integer> entry : playedSeconds.entrySet()) {
            long id = entry.getKey();
            observer.playerMatch(id,
                frozenMinutes.getOrDefault(id, 0.0),
                frozen.getOrDefault(id, 0.0),
                matchResiduals.getOrDefault(id, 0.0),
                entry.getValue() / 60.0,
                tallies.get(id).rating());
        }
    }


    // A player the run has not seen before has no tally yet, so there is
    // nothing to freeze: the getOrDefault at both read sites supplies the
    // debutant's 0.0, exactly as it did when this map held everybody.
    private static void freeze(long id, Map<Long, PlayerTally> tallies,
        Map<Long, Double> frozen, Map<Long, Double> frozenMinutes) {

            PlayerTally tally = tallies.get(id);
            if (tally != null) {
                frozen.put(id, tally.rating());
                frozenMinutes.put(id, tally.minutes());
            }
        }

    private void leavePitch(Map<Long, PlayerTally> tallies, Map<Long, Integer> enterTime,
        Map<Long, Integer> playedSeconds, Player p, Team team, int t) {
        Integer start = enterTime.remove(p.id());
        if (start != null) {
            tallies.computeIfAbsent(p.id(), k -> new PlayerTally(p, team)).addSeconds(t - start);
            playedSeconds.merge(p.id(), t - start, Integer::sum);
        }
    }


    private void closeSegment(Map<Long, Set<Player>> onPitch, long homeTeamId, RatingLookup ratings,
        Map<Long, PlayerTally> tallies, Map<Long, Double> matchResiduals, int from, int to) {
            if (to <= from || onPitch.size() != 2) {
                return; // zero-length segment, or lineups not both known yet
            }
            Iterator<Map.Entry<Long, Set<Player>>> teams = onPitch.entrySet().iterator();
            Map.Entry<Long, Set<Player>> teamA = teams.next();
            Map.Entry<Long, Set<Player>> teamB = teams.next();
            Map<Player, Double> deltas = residualSource.segment(
                new Lineup(teamA.getValue(), teamA.getKey() == homeTeamId, goalkeepers(teamA.getValue(), tallies)),
                new Lineup(teamB.getValue(), teamB.getKey() == homeTeamId, goalkeepers(teamB.getValue(), tallies)),
                to - from, ratings);
            for (Map.Entry<Player, Double> d : deltas.entrySet()) {
                matchResiduals.merge(d.getKey().id(), d.getValue(), Double::sum);
            }
        }

        // The on-pitch subset carrying the career Goalkeeper tag, read live:
        // the tag is stamped while StartingXI is processed, so today's starter
        // - even a career debutant - is always covered (item 11).
        private Set<Player> goalkeepers(Set<Player> onPitch, Map<Long, PlayerTally> tallies) {
            Set<Player> keepers = new HashSet<>();
            for (Player p : onPitch) {
                if (tallies.get(p.id()).isGoalkeeper()) {
                    keepers.add(p);
                }
            }
            return keepers;
        }
}
