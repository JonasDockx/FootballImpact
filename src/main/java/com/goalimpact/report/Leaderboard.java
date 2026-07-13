package com.goalimpact.report;

import com.goalimpact.engine.PlayerTally;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Leaderboard {
    
    private final double minMinutes;

    public Leaderboard(double minMinutes) {
        this.minMinutes = minMinutes;
    }

    public void print(Collection<PlayerTally> tallies, int topN) {
        List<PlayerTally> qualified = new ArrayList<>();
        for (PlayerTally pt : tallies) {
            if (pt.minutes() >= minMinutes) {
                qualified.add(pt);
            }
        }
        qualified.sort(Comparator.comparingDouble(PlayerTally::per90).reversed());

        System.out.printf("Goalimpact leaderboard (min %.0f minutes, %d qualified)%n%n",
            minMinutes, qualified.size());
        System.out.printf("%-4s %-26s %-14s %6s %6s %7s%n",
            "#", "Player", "Team", "Min", "Raw", "Per90");
        
        int rank = 1;
        for (PlayerTally pt : qualified) {
            if (rank > topN) break;
            System.out.printf(Locale.US, "%-4d %-26s %-14s %6.0f %6.1f %7.2f%n",
                rank, pt.player().name(), pt.team().name(),
                pt.minutes(), pt.rawTotal(), pt.per90());
            rank++;
        }
    }
}
