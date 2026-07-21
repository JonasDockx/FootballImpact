package com.goalimpact;

import com.goalimpact.credit.TimeIntegratedResidual;
import com.goalimpact.data.DataLoader;
import com.goalimpact.data.TransfermarktLoader;
import com.goalimpact.data.UnusableMatchException;
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
import java.util.TreeMap;

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

    // Item 11: the field-players-only Strength experiment. kept as a flag
    // because the experiment was run and lost. false = the shipped model
    // (Goalkeepers count in Strength), true = field players only. Tested
    // 2026-07-16 over an h sweep and an 81-cell (K0, H, h) grid: the
    // variant's best cell is these very knobs at 0.6261, so a Goalkeeper's
    // rating is worth 0.0002 of real predictive signal. Flip to {true} to
    // re-run on it.
    private static final boolean[] FIELD_PLAYERS_ONLY = {false};
    // The tuned all-players model (ADR 0008) - the number item 11 must beat.
    private static final double CHAMPION = 0.6259;
    
    // ADR 0009: exactly one spine per run. The same match arriving under
    // two sources' identities would be replayed twice, inflating exposure
    // and double-counting residuals - so this is a switch, never a merge.
    private enum Spine { STATSBOMB, TRANSFERMARKT }

    private static final Spine SPINE = Spine.TRANSFERMARKT;

    private static final Path STATSBOMB_DIR = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/statsbomb-open-data/data");
    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // Increment 2's vertical slice (ADR 0009): the league season increment 1
    // proved, plus a domestic cup and a finals tournament - which between
    // them exercise AWAY, NEITHER, the club finals rule, the tournament-host
    // rule, extra time and the skip-and-count path.
    private record Slice(String competitionId, String season) {
    }

    private static final List<Slice> SLICES = List.of(
        new Slice("GB1", "2024"),     // Premier League 2024/25, 380 matches
        new Slice("FAC", "2024"),     // FA Cup 2024/25, 123 matches
        new Slice("AFAC", "2024")       // AFC Asian Cup 2024, 51 matches, 44 usable
    );   


    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
                List<Match> matches = new ArrayList<>();
        List<List<MatchEvent>> replays = new ArrayList<>();
        switch (SPINE) {
            case STATSBOMB -> loadStatsBomb(matches, replays);
            case TRANSFERMARKT -> loadTransfermarkt(matches, replays);
        }
        System.out.printf("%nSpine: %s - %d matches replay (%s to %s).%n%n",
            SPINE, replays.size(),
            matches.get(0).date(), matches.get(matches.size() - 1).date());

        // The home-goal share, over matches that actually replayed and only
        // where a home side exists. Counted from goal EVENTS, never from the
        // scoreline: Transfermarkt folds penalty shootouts into
        // home_club_goals, which adds 135 phantom goals to the 123 FA Cup
        // ties in this slice alone and drags the measured share toward 50%.
        // This is the anchor h is re-measured against (ADR 0009), so it has
        // to count the same goals the model does.
        long homeGoals = 0, awayGoals = 0;
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            if (m.homeSide() == Match.HomeSide.NEITHER) {
                continue;
            }
            long homeTeamId = m.homeSide() == Match.HomeSide.HOME
                ? m.home().id()
                : m.away().id();
            for (MatchEvent e : replays.get(i)) {
                if (e instanceof MatchEvent.Goal goal) {
                    if (goal.scoringTeam().id() == homeTeamId) {
                        homeGoals++;
                    } else {
                        awayGoals++;
                    }
                }
            }
        }

        
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

    
    // The StatsBomb path, unchanged in behaviour: men's competitions only,
    // since women's football forms a disconnected rating island.
    private static void loadStatsBomb(List<Match> matches, List<List<MatchEvent>> replays)
        throws Exception {

        DataLoader loader = new DataLoader(STATSBOMB_DIR);
        List<Match> all = new ArrayList<>();
        System.out.printf("%-24s %-10s %5s %8s%n", "competition", "season", "home", "neutral");
        for (CompetitionSeason cs : loader.loadCompetitions()) {
            if (!cs.gender().equals("male")) {
                continue;
            }
            List<Match> loaded = loader.loadMatches(cs);
            long neutral = loaded.stream().filter(m -> m.homeSide() == Match.HomeSide.NEITHER).count();
            System.out.printf("%-24s %-10s %5d %8d%n",
                cs.competitionName(), cs.seasonName(), loaded.size() - neutral, neutral);
            all.addAll(loaded);
        }
        // Global replay order: date first; matchId as a deterministic
        // tie-break for matches on the same day.
        all.sort(Comparator.comparing(Match::date).thenComparingLong(Match::matchId));
        for (Match m : all) {
            if (loader.hasEvents(m.matchId())) {
                matches.add(m);
                replays.add(loader.loadEvents(m));
            }
        }
    }

    // The Transfermarkt spine (ADR 0009). A match that cannot form a
    // coherent replay is skipped and counted WITH ITS REASON: exposure
    // drives the update factor, so quietly thin data manufactures
    // false debutants.
    private static void loadTransfermarkt(List<Match> matches, List<List<MatchEvent>> replays)
        throws Exception {

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT)) {
            List<Match> all = new ArrayList<>();
            for (Slice slice : SLICES) {
                List<Match> loaded = loader.loadMatches(slice.competitionId(), slice.season());
                System.out.printf("%-5s %-6s %4d matches%n",
                    slice.competitionId(), slice.season(), loaded.size());
                all.addAll(loaded);
            }
            // One pooled run in date order: one spine is one rating pool, so
            // a player's cup, league and international minutes all move the
            // same rating, and every rating is read only from matches before.
            all.sort(Comparator.comparing(Match::date).thenComparingLong(Match::matchId));

            Map<String, Integer> skipped = new TreeMap<>();
            for (Match m : all) {
                try {
                    List<MatchEvent> events = loader.loadEvents(m);
                    matches.add(m);
                    replays.add(events);
                } catch (UnusableMatchException e) {
                    skipped.merge(e.getMessage(), 1, Integer::sum);
                }
            }
            System.out.printf("%d of %d matches replay, %d events dropped.%n",
                matches.size(), all.size(), loader.droppedEvents());
            skipped.forEach((reason, count) ->
                System.out.printf("  skipped %4d x %s%n", count, reason));

            // The venue verdict, which no test can eyeball for you.
            Map<Match.HomeSide, Integer> venues = new TreeMap<>();
            for (Match m : matches) {
                venues.merge(m.homeSide(), 1, Integer::sum);
            }
            System.out.println("  venue: " + venues);
        }
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
