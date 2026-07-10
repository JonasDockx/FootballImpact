package com.goalimpact;

import com.goalimpact.credit.FlatCreditRule;
import com.goalimpact.data.DataLoader;
import com.goalimpact.engine.MatchProcessor;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("C:/Users/dockx/Documents/Programmeren/DataStatsbomb/open-data/data");

        DataLoader loader = new DataLoader(dataDir);
        List<Match> matches = loader.loadMatches(55, 282);
        
        Match finalMatch = null;
        for (Match m : matches) {
            boolean spain = m.home().name().equals("Spain") || m.away().name().equals("Spain");
            boolean england = m.home().name().equals("England") || m.away().name().equals("England");
            if (spain && england) { finalMatch = m; break; }
        }

        List<MatchEvent> events = loader.loadEvents(finalMatch.matchId());

        MatchProcessor processor = new MatchProcessor(new FlatCreditRule());
        Map<Long, PlayerTally> tallies = new HashMap<>();
        processor.process(events, tallies);

        System.out.printf("%s %d-%d %s%n",
            finalMatch.home().name(), finalMatch.homeScore(),
            finalMatch.awayScore(), finalMatch.away().name());

        List<PlayerTally> ranked = new ArrayList<>(tallies.values());
        ranked.sort(Comparator.comparingDouble(PlayerTally::rawTotal).reversed());

        System.out.printf("%-26s %-10s %6s%n", "Player", "Team", "Min", "Raw");
        double sum = 0;
        for (PlayerTally pt : ranked) {
            System.out.printf("%-26s %-10s %6.0f %6.1f%n", pt.player().name(), pt.team().name(), pt.minutes(), pt.rawTotal());
            sum += pt.rawTotal();
        }
        System.out.printf("%nSum of raw totals (should be ~0): %+.1f%n", sum);
    }
}
