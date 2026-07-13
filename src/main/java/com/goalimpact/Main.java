package com.goalimpact;

import com.goalimpact.credit.FlatCreditRule;
import com.goalimpact.data.DataLoader;
import com.goalimpact.engine.MatchProcessor;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.model.CompetitionSeason;
import com.goalimpact.model.Match;
import com.goalimpact.report.CsvWriter;
import com.goalimpact.report.Leaderboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    private static final double MIN_MINUTES = 900;

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("C:/Users/dockx/Documents/Programmeren/DataStatsbomb/open-data/data");
        DataLoader loader = new DataLoader(dataDir);

        // Men's competitions only: women's football forms a disconnected
        // rating island, so mixing the two in one ranking would be meaningless.
        List<Match> matches = new ArrayList<>();
        for (CompetitionSeason cs : loader.loadCompetitions()) {
            if (!cs.gender().equals("male")) {
                continue;
            }
            List<Match> loaded = loader.loadMatches(cs.competitionId(), cs.seasonId());
            matches.addAll(loaded);
            System.out.printf("%-28s %-10s %5d matches%n",
                cs.competitionName(), cs.seasonName(), loaded.size());
        }

        // Global replay order: date first; matchId as a deterministic tie-break
        // for matches on the same day (kickoff times would be the upgrade).
        matches.sort(Comparator.comparing(Match::date).thenComparingLong(Match::matchId));

        System.out.printf("%nTotal: %d matches, %s to %s%n%n",
            matches.size(), matches.get(0).date(), matches.get(matches.size() - 1).date());

        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();
        int skipped = 0;
        for (Match m : matches) {
            if (!loader.hasEvents(m.matchId())) {
                skipped++;
                continue;
            }
            processor.process(loader.loadEvents(m.matchId()), tallies);
        }

        System.out.printf("Processed %d matches (%d without events skipped), %d players.%n%n",
            matches.size() - skipped, skipped, tallies.size());

        new Leaderboard(MIN_MINUTES).print(tallies.values(), 20);

        Path csv = Path.of("goalimpact.csv");
        new CsvWriter().write(csv, tallies.values());
        System.out.println();
        System.out.println("Full results written to " + csv.toAbsolutePath());
    }
}
