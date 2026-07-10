package com.goalimpact.engine;

import com.goalimpact.model.Player;
import com.goalimpact.model.Team;

public class PlayerTally {
    
    private final Player player;
    private final Team team;
    private double rawTotal;
    private long seconds;

    public PlayerTally(Player player, Team team) {
        this.player = player;
        this.team = team;
    }

    public void addValue(double delta) { this.rawTotal += delta; }
    public void addSeconds(long s) { this.seconds += s; }

    public Player player() { return player; }
    public Team team() { return team; }
    public double rawTotal() {return rawTotal; }
    public double minutes() { return seconds / 60.0; }

    public double per90() {
        if (seconds == 00) return 0.0;
        return rawTotal / (seconds / 5400.0); // 5400 seconds = 90 minutes
    }
}
