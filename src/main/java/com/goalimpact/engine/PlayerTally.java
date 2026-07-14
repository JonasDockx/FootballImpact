package com.goalimpact.engine;

import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

// A player's running state across the whole replay: the accumulated
// GoalImpact rating, plus total on-pitch time (kept for reporting only -
// minutes no longer normalize the rating).
public class PlayerTally {

    private final Player player;
    private Team team; // latest team seen: players change teams over a career
    private double rating;
    private long seconds;

    public PlayerTally(Player player, Team team) {
        this.player = player;
        this.team = team;
    }

    public void applyUpdate(double delta) { this.rating += delta; }
    public void addSeconds(long s) { this.seconds += s; }
    public void playsFor(Team team) { this.team = team; }

    public Player player() { return player; }
    public Team team() { return team; }
    public double rating() { return rating; }
    public double minutes() { return seconds / 60.0; }
}
