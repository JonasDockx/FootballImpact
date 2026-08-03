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
    private final RatingSeed seed;

    public MatchProcessor(ResidualSource residualSource, UpdateSchedule schedule) {
        this(residualSource, schedule, RatingSeed.AVERAGE);
    }

    // Item 16's seam. The two-argument form above is what every caller that
    // does not care about seeding still writes, and it is the status quo:
    // every debutant enters at the population mean.
    public MatchProcessor(ResidualSource residualSource, UpdateSchedule schedule,
        RatingSeed seed) {
        this.residualSource = residualSource;
        this.schedule = schedule;
        this.seed = seed;
    }

    // The two-argument form is the contract every existing caller has, and it
    // keeps it: no history, no observer, no ageing, no change. ADR 0011's seam
    // is the observer overload, ADR 0016's the four-argument one below.
    public void process(List<MatchEvent> events, Map<Long, PlayerTally> tallies) {
        process(events, tallies, MatchObserver.NONE);
    }

    public void process(List<MatchEvent> events, Map<Long, PlayerTally> tallies,
        MatchObserver observer) {
        process(events, tallies, AgePenalty.NONE, observer);
    }

    // ADR 0016. The age term arrives already bound to this match's kickoff
    // date (AgeingCurve.at), so the engine still knows nothing about a
    // calendar - it asks a lookup for a number, exactly as it does for a
    // rating. The forms above pass AgePenalty.NONE, which is the status quo.
    public void process(List<MatchEvent> events, Map<Long, PlayerTally> tallies,
        AgePenalty ageing, MatchObserver observer) {
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
        //
        // ADR 0016 splits what used to be one map in two, because the stored
        // number and the strength number stopped being the same thing. frozen
        // holds P, the player's estimated peak - the thing the update moves and
        // the thing the history records. strength holds P - D(age at kickoff),
        // which is what he actually contributes today and all the credit rule
        // ever sees. The age term is read ONCE per player here rather than on
        // every rating read, and it cannot change inside a match.
        Map<Long, Double> frozen = new HashMap<>();
        Map<Long, Double> strength = new HashMap<>();
        Map<Long, Double> frozenMinutes = new HashMap<>();
        for (MatchEvent e : events) {
            switch(e) {
                case MatchEvent.StartingXI s -> {
                    for (Player p : s.players()) {
                        freeze(p.id(), s.team(), tallies, ageing, frozen, strength, frozenMinutes);
                    }
                }
                case MatchEvent.Substitution sub ->
                    freeze(sub.playerOn().id(), sub.team(), tallies, ageing,
                        frozen, strength, frozenMinutes);
                default -> { }
            }
        }
        RatingLookup preMatch = id -> strength.getOrDefault(id, 0.0);

        Map<Long, Set<Player>> onPitch = new HashMap<>(); // teamId -> players currently on
        Map<Long, Integer> enterTime = new HashMap<>(); // playerId -> stint start (seconds)
        Map<Long, Double> matchResiduals = new HashMap<>(); // playerId -> summed residuals
        Map<Long, Integer> playedSeconds = new HashMap<>(); // playerId -> on-pitch seconds THIS match

        // #24: the same residuals again, kept apart this time - goal values
        // here, drained expectation there. Two extra maps rather than a
        // subtraction at the whistle, because matchResiduals is summed in event
        // order and that order is the rating: deriving either half from it, or
        // it from the halves, would re-associate a floating-point sum that has
        // to stay bit-for-bit what it was. Nothing reads these but the observer,
        // so a run without one pays two map writes per goal and segment and
        // changes not one rating.
        Map<Long, Double> goalValues = new HashMap<>();  // playerId -> summed +-1 goal values
        Map<Long, Double> drained = new HashMap<>();     // playerId -> summed expectation drain


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
                        tallyFor(p, s.team(), tallies).playsFor(s.team());
                    }
                    tallies.get(s.goalkeeper().id()).startedInGoal();
                    if (s.home()) {
                        homeTeamId = s.team().id();
                    }
                }
                case MatchEvent.Substitution sub -> {
                    closeSegment(onPitch, homeTeamId, preMatch, tallies, matchResiduals, drained,
                        segStart, t);
                    segStart = t;
                    onPitch.get(sub.team().id()).remove(sub.playerOff());
                    leavePitch(tallies, enterTime, playedSeconds, sub.playerOff(), sub.team(), t);


                    onPitch.get(sub.team().id()).add(sub.playerOn());
                    enterTime.put(sub.playerOn().id(), t);
                    tallyFor(sub.playerOn(), sub.team(), tallies).playsFor(sub.team());
                }
                case MatchEvent.RedCard rc -> {
                    closeSegment(onPitch, homeTeamId, preMatch, tallies, matchResiduals, drained,
                        segStart, t);
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
                        goalValues.merge(d.getKey().id(), d.getValue(), Double::sum);
                    }
                }
                case MatchEvent.MatchEnd end -> {
                    // The whistle closes the final segment; its timestamp already
                    // became lastTime, which closes every open stint.
                    closeSegment(onPitch, homeTeamId, preMatch, tallies, matchResiduals, drained,
                        segStart, t);
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
            observer.playerMatch(new PlayerMatch(id,
                frozenMinutes.getOrDefault(id, 0.0),
                frozen.getOrDefault(id, 0.0),
                goalValues.getOrDefault(id, 0.0),
                drained.getOrDefault(id, 0.0),
                matchResiduals.getOrDefault(id, 0.0),
                entry.getValue() / 60.0,
                tallies.get(id).rating()));
        }
    }


    // A player the run has not seen before has no tally yet, so his pre-match
    // rating is whatever the RatingSeed says a debutant at this club starts
    // at - written in here rather than left to the read sites' getOrDefault,
    // because his own debut must be priced at the seed too. Exposure is
    // untouched: a debutant has none, so frozenMinutes stays absent and the
    // getOrDefault at the update site still supplies 0.0.
    //
    // Under RatingSeed.AVERAGE this puts 0.0 where the read sites would have
    // defaulted to 0.0, so it is byte-identical to leaving the key out.
    //
    // ADR 0016: the same pass fixes what he is worth TODAY, his peak less the
    // age term. A debutant is seeded at his peak and then aged like everyone
    // else, which is exactly the age-aware prior #42 asked for - a
    // seventeen-year-old and a twenty-seven-year-old no longer enter the run
    // as the same player.
    //
    // EVERY man is aged here, Goalkeepers included, and ADR 0016 says the curve
    // is field players only. That is a contradiction the flat stage 1 curve
    // hides and stage 2 must not: a keeper's career runs on a different clock,
    // and what he should be charged instead is the open question on #44. Stage 2
    // does not ship until #44 answers it, and this is where the answer lands.
    private void freeze(long id, Team team, Map<Long, PlayerTally> tallies, AgePenalty ageing,
        Map<Long, Double> frozen, Map<Long, Double> strength, Map<Long, Double> frozenMinutes) {

        PlayerTally tally = tallies.get(id);
        double peak = tally == null ? seed.forDebutant(team) : tally.rating();
        frozen.put(id, peak);
        strength.put(id, peak - ageing.forPlayer(id));
        if (tally != null) {
            frozenMinutes.put(id, tally.minutes());
        }
    }

    // Every place a tally is born. A player's rating starts at his seed and
    // moves from there; a player already in the run keeps the tally he has, so
    // he is seeded exactly once, at the club he was first seen at.
    private PlayerTally tallyFor(Player p, Team team, Map<Long, PlayerTally> tallies) {
        return tallies.computeIfAbsent(p.id(),
            k -> new PlayerTally(p, team, seed.forDebutant(team)));
    }

    private void leavePitch(Map<Long, PlayerTally> tallies, Map<Long, Integer> enterTime,
        Map<Long, Integer> playedSeconds, Player p, Team team, int t) {
        Integer start = enterTime.remove(p.id());
        if (start != null) {
            tallyFor(p, team, tallies).addSeconds(t - start);
            playedSeconds.merge(p.id(), t - start, Integer::sum);
        }
    }


    private void closeSegment(Map<Long, Set<Player>> onPitch, long homeTeamId, RatingLookup ratings,
        Map<Long, PlayerTally> tallies, Map<Long, Double> matchResiduals,
        Map<Long, Double> drained, int from, int to) {
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
                drained.merge(d.getKey().id(), d.getValue(), Double::sum);
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
