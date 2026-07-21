package com.goalimpact;

import com.goalimpact.credit.TimeIntegratedResidual;
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
    private static final double[] K0S = {1.0};
    private static final double[] HALVING_MINUTES = {4000};
    private static final double[] FLOOR_FRACTIONS = {0.05};
    // Measured 2026-07-15 on the honest clock (stage 2, ADR 0007):
    // 7,496 goals / 509,022 team-minutes. Re-measure when new eras land.
    private static final double BASE_RATE = 0.01473;
    // Stage 1 baseline (ADR 0008): the venue-blind model's log-loss - the
    // number home advantage must strictly beat (parity is failure here:
    // this knob targets the measured quantity directly.)
    private static final double VENUE_BLIND_BASELINE = 0.6326;
    // ADR 0008: home advantage in rating points, added to the home side's
    // effective gap. Grid-tuned 2026-07-16: winner 2.5 (logloss 0.6259),
    // interior on a 0-4 sweep, beside the measured anchor 2.69; 0.0
    // recovers the venue-blind baseline.
    private static final double[] HOME_ADVANTAGES = {2.5};

    // Item 11: the field-players-only Strength experiment. false = status
    // quo (Goalkeepers count in Strength), true = field players only. Gate:
    // the variant's best cell must strictly beat the all-players champion;
    // a tie keeps Goalkeepers in Strength.
    private static final boolean[] FIELD_PLAYERS_ONLY = {false};
    // The tuned all-players model (ADR 0008) - the number item 11 must beat.
    private static final double CHAMPION = 0.6259;

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        Path dataDir = Path.of("C:/Users/dockx/Documents/Programmeren/FootballData/statsbomb-open-data/data");
        DataLoader loader = new DataLoader(dataDir);

        // Men's competitions only: women's football forms a disconnected
        // rating island, so mixing the two in one ranking would be meaningless.
        List<Match> matches = new ArrayList<>();
        System.out.printf("%-24s %-10s %5s %8s%n", "competition", "season", "home", "neutral");
        for (CompetitionSeason cs : loader.loadCompetitions()) {
            if (!cs.gender().equals("male")) {
                continue;
            }
            List<Match> loaded = loader.loadMatches(cs);
            long neutral = loaded.stream().filter(m -> m.homeSide() == Match.HomeSide.NEITHER).count();
            System.out.printf("%-24s %-10s %5d %8d%n",
                cs.competitionName(), cs.seasonName(), loaded.size() - neutral, neutral);
            matches.addAll(loaded);
        }
        System.out.println();

        // Global replay order: date first; matchId as a deterministic tie-break
        // for matches on the same day (kickoff times would be the upgrade).
        matches.sort(Comparator.comparing(Match::date).thenComparingLong(Match::matchId));

        // Parse every events file once; each grid cell then replays from memory.
        List<List<MatchEvent>> replays = new ArrayList<>();
        long homeGoals = 0, awayGoals = 0; // scored by / conceded by home sides
        for (Match m : matches) {
            if (loader.hasEvents(m.matchId())) {
                replays.add(loader.loadEvents(m));
                if (m.homeSide() == Match.HomeSide.HOME) {
                    homeGoals += m.homeScore();
                    awayGoals += m.awayScore();
                } else if (m.homeSide() == Match.HomeSide.AWAY) {
                    homeGoals += m.awayScore();
                    awayGoals += m.homeScore();
                }
            }
        }
        System.out.printf("Loaded %d of %d matches (%s to %s).%n%n",
            replays.size(), matches.size(),
            matches.get(0).date(), matches.get(matches.size() - 1).date());
        
        // Base scoring rate (ADR 0007): goals per team-minute of play across
        // the whole male dataset - a measured calibration constant, not a
        // tuned knob. Re-measure when large new eras/competitions land.
        long goals = 0;
        double teamMinutes = 0;
        for (List<MatchEvent> events : replays) {
            for (MatchEvent e : events) {
                if (e instanceof MatchEvent.Goal) {
                    goals++;
                } else if (e instanceof MatchEvent.MatchEnd end) {
                    teamMinutes += 2 * (end.minute() + end.second() / 60.0);
                }
            }
        }
        System.out.printf(Locale.US,
            "Base scoring rate: %d goals / %.0f team-minutes = %.5f goals per team-minute%n%n",
            goals, teamMinutes, goals / teamMinutes);

        System.out.printf(Locale.US,
            "Home sides scored %d of %d goals where a home side exists (%.1f%%)"
            + " -> anchor: h =~ ln(ratio)/k : %.2f rating points%n%n",
            homeGoals, homeGoals + awayGoals,
            100.0 * homeGoals / (homeGoals + awayGoals),
            Math.log((double) homeGoals / awayGoals) / LINK_GAINS[0]);

        // Grid search: prequential mean log-loss per (k, K). 0.6931 = ln 2 is
        // the know-nothing baseline; lower is better.
        System.out.printf("%8s %8s %8s %8s %8s %8s %10s%n", "k", "K0", "H", "floor", "home", "field", "logloss");
        double bestGain = 0, bestK0 = 0, bestH = 0, bestFloor = 0, bestHome = 0, bestLoss = Double.MAX_VALUE;
        boolean bestFieldOnly = false;
        for (double gain : LINK_GAINS) {
            for (double k0 : K0S) {
                for (double h : HALVING_MINUTES) {
                    for (double floor : FLOOR_FRACTIONS) {
                        for (double home : HOME_ADVANTAGES) {
                            for (boolean fieldOnly : FIELD_PLAYERS_ONLY ) {
                                PredictionQuality quality = new PredictionQuality();
                                replay(replays, gain, home, fieldOnly, new SmoothFadeSchedule(k0, h, floor), quality::observe);
                                double loss = quality.meanLogLoss();
                                System.out.printf(Locale.US, "%8.2f %8.2f %8.0f %8.2f %8.2f %8s %10.4f%n",
                                    gain, k0, h, floor, home, fieldOnly ? "yes" : "no", loss);
                                if (loss < bestLoss) {
                                    bestLoss = loss;
                                    bestGain = gain;
                                    bestK0 = k0;
                                    bestH = h;
                                    bestFloor = floor;
                                    bestHome = home;
                                    bestFieldOnly = fieldOnly;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (bestLoss == Double.MAX_VALUE) {
            throw new IllegalStateException("grid search saw no goals - cannot pick knobs.");
        }
        System.out.printf(Locale.US,
            "%nBest: k=%.2f K0=%.2f H=%.0f floor=%.2f home=%.2f fieldOnly=%s (logloss %.4f vs 0.6931 know-nothing)%n",
            bestGain, bestK0, bestH, bestFloor, bestHome, bestFieldOnly ? "yes" : "no", bestLoss);
        System.out.printf(Locale.US,
            "Ship gate (ADR 0008): %.4f vs %.4f venue-blind baseline -> %s%n%n",
            bestLoss, VENUE_BLIND_BASELINE,
            bestLoss < VENUE_BLIND_BASELINE ? "strictly better" : "NOT strictly better - do not ship");
        System.out.printf(Locale.US,
            "Item 11 gate: best %.4f (field-players-only: %s) vs %.4f all-players champion -> %s%n%n",
            bestLoss, bestFieldOnly ? "yes": "no", CHAMPION,
            bestFieldOnly && bestLoss < CHAMPION
                ? "adop field-player-only-Strength" : "keep Goalkeepers in Strength");

        // Final replay with the winning knobs; reports come from this one.
        Map<Long, PlayerTally> tallies = replay(replays, bestGain, bestHome, bestFieldOnly, new SmoothFadeSchedule(bestK0, bestH, bestFloor), p -> {});

        new Leaderboard().print(tallies.values(), 20);

        Path csv = Path.of("goalimpact.csv");
        new CsvWriter().write(csv, tallies.values());
        System.out.println();
        System.out.println("Full results written to " + csv.toAbsolutePath());
    }

    // One full chronological replay of all matches with the given knobs;
    // returns the resulting tallies, pObserver hears every goal's expected P.
    private static Map<Long, PlayerTally> replay(List<List<MatchEvent>> replays,
        double linkGain, double homeAdvantage, boolean fieldPlayersOnly, UpdateSchedule schedule, DoubleConsumer pObserver) {
            MatchProcessor processor = new MatchProcessor(
                new TimeIntegratedResidual(BASE_RATE, linkGain, homeAdvantage, fieldPlayersOnly, pObserver), schedule);
            Map<Long, PlayerTally> tallies = new HashMap<>();
            for (List<MatchEvent> events : replays) {
                processor.process(events, tallies);
            }
            return tallies;
        }
}
