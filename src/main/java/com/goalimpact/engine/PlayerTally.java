package com.goalimpact.engine;

import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

// A player's running state across the whole replay: the accumulated
// GoalImpact rating, plus total on-pitch time - the exposure that sizes the
// player's update factor (ADR 0006) and feeds reporting.
//
// ADR 0016 changed what the rating MEANS without changing how it moves. It is
// now P, the player's estimated PEAK - Peak Impact - and what he contributes
// to a lineup today is P minus the ageing curve's penalty for his age that
// day. The update is untouched: residuals still land on P, with ADR 0006's K
// schedule as-is. While the curve is pinned flat (stage 1) the two numbers
// coincide, so nothing downstream reads differently yet.
public class PlayerTally {

    private final Player player;
    private Team team; // latest team seen: players change teams over a career
    private double rating;
    private long seconds;
    private boolean goalkeeper; // sticky: ever started in goal, per the glossary's career-level tag

    public PlayerTally(Player player, Team team) {
        this(player, team, 0.0);
    }

    // Item 16: a career starts where the RatingSeed puts it, which is 0.0 -
    // the population mean - for everyone except a player first seen at a club
    // the run never prices.
    public PlayerTally(Player player, Team team, double seed) {
        this.player = player;
        this.team = team;
        this.rating = seed;
    }

    public void applyUpdate(double delta) { this.rating += delta; }
    public void addSeconds(long s) { this.seconds += s; }
    public void playsFor(Team team) { this.team = team; }
    public void startedInGoal() { this.goalkeeper = true; }

    public Player player() { return player; }
    public Team team() { return team; }
    public double rating() { return rating; }
    public boolean isGoalkeeper() { return goalkeeper; }
    public double minutes() { return seconds / 60.0; }
}
