package com.goalimpact.engine;

import com.goalimpact.credit.CreditRule;
import com.goalimpact.credit.RatingLookup;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MatchProcessor {
    
    private final CreditRule creditRule;
    private final UpdateSchedule schedule;

    public MatchProcessor(CreditRule creditRule, UpdateSchedule schedule) {
        this.creditRule = creditRule;
        this.schedule = schedule;
    }

    public void process(List<MatchEvent> events, Map<Long, PlayerTally> tallies) {
        // Rating period: freeze every player's rating AND exposure at their
        // pre-match values. Every goal is judged against the frozen ratings,
        // every update sized by the frozen exposure; updates apply only at
        // the final whistle.
        Map<Long, Double> frozen = new HashMap<>();
        Map<Long, Double> frozenMinutes = new HashMap<>();
        for (Map.Entry<Long, PlayerTally> entry : tallies.entrySet()) {
            frozen.put(entry.getKey(), entry.getValue().rating());
            frozenMinutes.put(entry.getKey(), entry.getValue().minutes());
        }
        RatingLookup preMatch = id -> frozen.getOrDefault(id, 0.0);

        Map<Long, Set<Player>> onPitch = new HashMap<>(); // teamId -> players currently on
        Map<Long, Integer> enterTime = new HashMap<>(); // playerId -> stint start (seconds)
        Map<Long, Double> matchResiduals = new HashMap<>(); // playerId -> summed residuals

        int lastTime = 0;

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
                }
                case MatchEvent.Substitution sub -> {
                    onPitch.get(sub.team().id()).remove(sub.playerOff());
                    leavePitch(tallies, enterTime, sub.playerOff(), sub.team(), t);

                    onPitch.get(sub.team().id()).add(sub.playerOn());
                    enterTime.put(sub.playerOn().id(), t);
                    tallies.computeIfAbsent(sub.playerOn().id(), k -> new PlayerTally(sub.playerOn(), sub.team()))
                        .playsFor(sub.team());
                }
                case MatchEvent.RedCard rc -> {
                    onPitch.get(rc.team().id()).remove(rc.player());
                    leavePitch(tallies, enterTime, rc.player(), rc.team(), t);
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

                    Map<Player, Double> deltas = creditRule.credit(scoringOnPitch, concedingOnPitch, preMatch);
                    for (Map.Entry<Player, Double> d : deltas.entrySet()) {
                        matchResiduals.merge(d.getKey().id(), d.getValue(), Double::sum);
                    }
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
        }
    }

    private void leavePitch(Map<Long, PlayerTally> tallies, Map<Long, Integer> enterTime, Player p, Team team, int t) {
        Integer start = enterTime.remove(p.id());
        if (start != null) {
            tallies.computeIfAbsent(p.id(), k -> new PlayerTally(p, team)).addSeconds(t - start);
        }
    }
}
