package com.goalimpact;

import com.goalimpact.credit.LogisticLinkFunction;
import com.goalimpact.credit.ResidualCreditRule;
import com.goalimpact.data.DataLoader;
import com.goalimpact.engine.MatchProcessor;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.engine.PredictionQuality;
import com.goalimpact.model.CompetitionSeason;
import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.report.CsvWriter;
import com.goalimpact.report.Leaderboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Main {

    // The two empirical knobs of rule C (see ADR 0005), grid-searched below:
    // k - link gain: how strongly a rating gap moves the expected outcome
    // K - update factor: how much of a match's residual enters the rating
    private static final double[] LINK_GAINS = {0.05, 0.1, 0.2, 0.5, 1.0};
    private static final double[] K_FACTORS = {0.1, 0.25, 0.5, 1.0};

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
            matches.addAll(loader.loadMatches(cs.competitionId(), cs.seasonId()));
        }

        // Global replay order: date first; matchId as a deterministic tie-break
        // for matches on the same day (kickoff times would be the upgrade).
        matches.sort(Comparator.comparing(Match::date).thenComparingLong(Match::matchId));

        // Parse every events file once; each grid cell then replays from memory.
        List<List<MatchEvent>> replays = new ArrayList<>();
        for (Match m : matches) {
            if (loader.hasEvents(m.matchId())) {
                replays.add(loader.loadEvents(m.matchId()));
            }
        }
        System.out.printf("Loaded %d of %d matches (%s to %s).%n%n",
            replays.size(), matches.size(),
            matches.get(0).date(), matches.get(matches.size() - 1).date());

        // Grid search: prequential mean log-loss per (k, K). 0.6931 = ln 2 is
        // the know-nothing baseline; lower is better.
        System.out.printf("%8s %8s %10s%n", "k", "K", "logloss");
        double bestGain = 0, bestKFactor = 0, bestLoss = Double.MAX_VALUE;
        for (double gain : LINK_GAINS) {
            for (double kFactor : K_FACTORS) {
                PredictionQuality quality = new PredictionQuality();
                MatchProcessor processor = new MatchProcessor(
                    new ResidualCreditRule(new LogisticLinkFunction(gain), quality::observe),
                    kFactor);
                Map<Long, PlayerTally> tallies = new HashMap<>();
                for (List<MatchEvent> events : replays) {
                    processor.process(events, tallies);
                }
                System.out.printf(Locale.US, "%8.2f %8.2f %10.4f%n",
                    gain, kFactor, quality.meanLogLoss());
                if (quality.meanLogLoss() < bestLoss) {
                    bestLoss = quality.meanLogLoss();
                    bestGain = gain;
                    bestKFactor = kFactor;
                }
                if (bestLoss == Double.MAX_VALUE) {
                    throw new IllegalStateException("grid search saw no goals - cannot pick knobs.");
                }
            }
        }
        System.out.printf(Locale.US,
            "%nBest: k=%.2f K=%.2f (logloss %.4f vs 0.6931 baseline)%n%n",
            bestGain, bestKFactor, bestLoss);

        // Final replay with the winning knobs; reports come from this one.
        MatchProcessor processor = new MatchProcessor(
            new ResidualCreditRule(new LogisticLinkFunction(bestGain)), bestKFactor);
        Map<Long, PlayerTally> tallies = new HashMap<>();
        for (List<MatchEvent> events : replays) {
            processor.process(events, tallies);
        }

        new Leaderboard().print(tallies.values(), 20);

        Path csv = Path.of("goalimpact.csv");
        new CsvWriter().write(csv, tallies.values());
        System.out.println();
        System.out.println("Full results written to " + csv.toAbsolutePath());
    }
}
