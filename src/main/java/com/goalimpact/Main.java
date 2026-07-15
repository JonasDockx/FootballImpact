package com.goalimpact;

import com.goalimpact.credit.LogisticLinkFunction;
import com.goalimpact.credit.ResidualCreditRule;
import com.goalimpact.data.DataLoader;
import com.goalimpact.engine.MatchProcessor;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.engine.PredictionQuality;
import com.goalimpact.engine.SmoothFadeSchedule;
import com.goalimpact.engine.UpdateSchedule;
import com.goalimpact.model.CompetitionSeason;
import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.report.CsvWriter;
import com.goalimpact.report.Leaderboard;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Main {
    // The empirical knobs of rule C + ADR 0006, grid-searched below:
    // k    - link gain: how strongly a rating gap moves the expected outcome.
    // K0   - update factor for a debutant (zero exposure)
    // H    - halving exposure: career minutes at which updates halve.
    // floor    - fraction of K0 below which updates never fade;
    //            1.0 switches the fade off = the uniform-K baseline (ship gate)
    private static final double[] LINK_GAINS = {0.10};
    private static final double[] K0S = {2.0};
    private static final double[] HALVING_MINUTES = {4000};
    private static final double[] FLOOR_FRACTIONS = {0.05};

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
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
                System.out.printf("%8s %8s %8s %8s %10s%n", "k", "K0", "H", "floor", "logloss");
        double bestGain = 0, bestK0 = 0, bestH = 0, bestFloor = 0, bestLoss = Double.MAX_VALUE;
        double bestUniformLoss = Double.MAX_VALUE;
        for (double gain : LINK_GAINS) {
            for (double k0 : K0S) {
                for (double h : HALVING_MINUTES) {
                    for (double floor : FLOOR_FRACTIONS) {
                        // At floor 1.0 the fade is off and H is irrelevant:
                        // run that uniform baseline once, not once per H.
                        if (floor == 1.0 && h != HALVING_MINUTES[0]) {
                            continue;
                        }
                        PredictionQuality quality = new PredictionQuality();
                        replay(replays, gain, new SmoothFadeSchedule(k0, h, floor), quality::observe);
                        double loss = quality.meanLogLoss();
                        System.out.printf(Locale.US, "%8.2f %8.2f %8.0f %8.2f %10.4f%n",
                            gain, k0, h, floor, loss);
                        if (floor == 1.0 && loss < bestUniformLoss) {
                            bestUniformLoss = loss;
                        }
                        if (loss < bestLoss) {
                            bestLoss = loss;
                            bestGain = gain;
                            bestK0 = k0;
                            bestH = h;
                            bestFloor = floor;
                        }
                    }
                }
            }
        }
        if (bestLoss == Double.MAX_VALUE) {
            throw new IllegalStateException("grid search saw no goals - cannot pick knobs.");
        }
        System.out.printf(Locale.US,
            "%nBest: k=%.2f K0=%.2f H=%.0f floor=%.2f (logloss %.4f vs 0.6931 know-nothing)%n",
            bestGain, bestK0, bestH, bestFloor, bestLoss);
        System.out.printf(Locale.US,
            "Ship gate (ADR 0006): best uniform logloss %.4f -> %s%n%n",
            bestUniformLoss,
            bestLoss < bestUniformLoss
                ? "adaptive K beats uniform"
                : "adaptive K does NOT beat uniform");

        // Final replay with the winning knobs; reports come from this one.
        Map<Long, PlayerTally> tallies = replay(replays, bestGain, new SmoothFadeSchedule(bestK0, bestH, bestFloor), p -> {});

        new Leaderboard().print(tallies.values(), 20);

        Path csv = Path.of("goalimpact.csv");
        new CsvWriter().write(csv, tallies.values());
        System.out.println();
        System.out.println("Full results written to " + csv.toAbsolutePath());
    }

    // One full chronological replay of all matches with the given knobs;
    // returns the resulting tallies, pObserver hears every goal's expected P.
    private static Map<Long, PlayerTally> replay(List<List<MatchEvent>> replays,
        double linkGain, UpdateSchedule schedule, DoubleConsumer pObserver) {
            MatchProcessor processor = new MatchProcessor(
                new ResidualCreditRule(new LogisticLinkFunction(linkGain), pObserver), schedule);
            Map<Long, PlayerTally> tallies = new HashMap<>();
            for (List<MatchEvent> events : replays) {
                processor.process(events, tallies);
            }
            return tallies;
        }
}
