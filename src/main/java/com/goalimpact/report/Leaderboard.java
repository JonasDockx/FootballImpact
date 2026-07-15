package com.goalimpact.report;

import com.goalimpact.engine.PlayerTally;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Leaderboard {

    public void print(Collection<PlayerTally> tallies, int topN) {
        List<PlayerTally> ranked = new ArrayList<>();
        for (PlayerTally pt : tallies) {
            if (!pt.isGoalkeeper()) {
                ranked.add(pt);
            }
        }
        ranked.sort(Comparator.comparingDouble(PlayerTally::rating).reversed());

        System.out.printf("GoalImpact leaderboard - field players only (%d of %d players; goalkeepers in the CSV)%n%n", ranked.size(), tallies.size());
        System.out.printf("%-4s %-28s %-24s %7s %8s%n",
            "#", "Player", "Team", "Min", "Rating");

        int rank = 1;
        for (PlayerTally pt : ranked) {
            if (rank > topN) break;
            System.out.printf(Locale.US, "%-4d %-28s %-24s %7.0f %8.2f%n",
                rank, pt.player().name(), pt.team().name(),
                pt.minutes(), pt.rating());
            rank++;
        }
    }
}
