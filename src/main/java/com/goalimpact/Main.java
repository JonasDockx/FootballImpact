package com.goalimpact;

import com.goalimpact.credit.FlatCreditRule;
import com.goalimpact.data.DataLoader;
import com.goalimpact.engine.MatchProcessor;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.report.CsvWriter;
import com.goalimpact.report.Leaderboard;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    private static final double MIN_MINUTES = 270;
    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("C:/Users/dockx/Documents/Programmeren/DataStatsbomb/open-data/data");

        DataLoader loader = new DataLoader(dataDir);
        List<Match> matches = loader.loadMatches(55, 282);
        matches.sort(Comparator.comparing(Match::date));

        System.out.printf("Matches span %s to %s%n%n",
            matches.get(0).date(), matches.get(matches.size() - 1).date());

        MatchProcessor processor = new MatchProcessor(new FlatCreditRule());
        Map<Long, PlayerTally> tallies = new HashMap<>();
        for (Match m : matches) {
            List<MatchEvent> events = loader.loadEvents(m.matchId());
            processor.process(events, tallies);
        }

        System.out.printf("Processed %d matches, %d player.%n%n",
                matches.size(), tallies.size());

        new Leaderboard(MIN_MINUTES).print(tallies.values(), 20);

        Path csv = Path.of("goalimpact-euro2024.csv");
        new CsvWriter().write(csv, tallies.values());
        System.out.println();
        System.out.println("Full results written to " + csv.toAbsolutePath());
    }
}
