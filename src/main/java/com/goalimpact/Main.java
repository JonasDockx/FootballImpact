package com.goalimpact;

import com.goalimpact.credit.TimeIntegratedResidual;
import com.goalimpact.data.DataLoader;
import com.goalimpact.data.HeldAppearance;
import com.goalimpact.data.TransfermarktLoader;
import com.goalimpact.data.UnusableMatchException;
import com.goalimpact.engine.MatchObserver;
import com.goalimpact.engine.MatchProcessor;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.engine.PredictionQuality;
import com.goalimpact.engine.SmoothFadeSchedule;
import com.goalimpact.engine.UpdateSchedule;
import com.goalimpact.model.CompetitionSeason;
import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.report.CsvWriter;
import com.goalimpact.report.HeldAppearanceWriter;
import com.goalimpact.report.Leaderboard;
import com.goalimpact.report.RatingHistoryWriter;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    // Item 11: the field-players-only Strength experiment. kept as a flag
    // because the experiment was run and lost. false = the shipped model
    // (Goalkeepers count in Strength), true = field players only. Tested
    // 2026-07-16 over an h sweep and an 81-cell (K0, H, h) grid: the
    // variant's best cell is these very knobs at 0.6261, so a Goalkeeper's
    // rating is worth 0.0002 of real predictive signal. Flip to {true} to
    // re-run on it.
    private static final boolean[] FIELD_PLAYERS_ONLY = {false};
    
    // ADR 0009: exactly one spine per run. The same match arriving under
    // two sources' identities would be replayed twice, inflating exposure
    // and double-counting residuals - so this is a switch, never a merge.
    private enum Spine { STATSBOMB, TRANSFERMARKT }

    private static final Spine SPINE = Spine.TRANSFERMARKT;

    // The base scoring rate is a property of the POPULATION, not of the
    // model, so each spine carries its own measured value. Neither is a
    // knob and neither is tuned - both are plain counts of goals over
    // team-minutes (CONTEXT 'Base scoring rate'; ADR 0007).
    //
    // StatsBomb, measured 2026-07-15 on the honest clock (ADR 0007 stage 2):
    //   7,496 goals / 509,022 team-minutes.
    // Transfermarkt, measured 2026-07-22 on the full ingest:
    //   223,810 goals / 14,610,840 team-minutes over 80,471 matches,
    //   2013-07-02 to 2026-07-06, 65 competitions. Higher than StatsBomb's
    //   for two reasons at once - a broader population, and a nominal clock
    //   whose denominator counts no stoppage time (ADR 0009).
    private static final double BASE_RATE = switch (SPINE) {
        case STATSBOMB -> 0.01473;
        case TRANSFERMARKT -> 0.01532;
    };

    // ADR 0008: home advantage in rating points, added to the home side's
    // effective gap. Population-specific, like the base rate, so each spine
    // carries its own tuned value.
    //   StatsBomb     2026-07-16: 2.5, beside that population's 2.69 anchor.
    //   Transfermarkt 2026-07-22: 2.0, from an 81-cell grid over 80,471
    //     matches. Interior on a 0-4 sweep that is a clean U - 0.6551 at
    //     h=0, 0.6502 at h=2.0, 0.6552 at h=4.0 - and beside the
    //     league-only measured anchor of 2.32. Both spines agree home
    //     advantage is real and worth about two rating points.
    private static final double[] HOME_ADVANTAGES = switch (SPINE) {
        case STATSBOMB -> new double[] {2.5};
        case TRANSFERMARKT -> new double[] {2.0};
    };

    // The venue-blind baseline: the same grid's h = 0.0 cell, which is the
    // number home advantage must strictly beat (parity is failure - this
    // knob targets the measured quantity directly).
    //   StatsBomb     2026-07-16 (ADR 0008 stage 1): 0.6326.
    //   Transfermarkt 2026-07-22, full ingest: 0.6551.
    private static final double VENUE_BLIND_BASELINE = switch (SPINE) {
        case STATSBOMB -> 0.6326;
        case TRANSFERMARKT -> 0.6551;
    };

    // The current champion: what a new experiment must beat, and the
    // regression check that the shipped model still scores what it did.
    //   StatsBomb     2026-07-16: 0.6259 (k .10, K0 1.0, H 4000, h 2.5).
    //   Transfermarkt 2026-07-22: 0.6502 (k .10, K0 1.0, H 4000, h 2.0),
    //     scored from 2015-07-01; 0.6508 over the whole replay.
    private static final double CHAMPION = switch (SPINE) {
        case STATSBOMB -> 0.6259;
        case TRANSFERMARKT -> 0.6502;
    };
    
    // The window over which predictions are GRADED - not the window that is
    // replayed. Lineups start in July 2013, so for the first two seasons
    // every rating is still near zero and every prediction near 50/50, for
    // reasons that have nothing to do with the knobs. Grading those rewards
    // whichever settings climb away from zero fastest, which is a different
    // question from which settings predict football best. Two seasons is
    // roughly H = 4,000 minutes twice over, so a regular starter's rating
    // has settled by then.
    //
    // This is NOT the warm-up pass ADR 0009 rejected. That one seeded 2013's
    // ratings from 2014's matches, which is acausal. Here no rating changes
    // at all, the replay stays strictly chronological, and every rating
    // still reads only matches played before it. Only the grading changes.
    // StatsBomb keeps its whole window, so its pinned number is untouched.
    private static final LocalDate SCORING_FROM = switch (SPINE) {
        case STATSBOMB -> LocalDate.MIN;
        case TRANSFERMARKT -> LocalDate.of(2015, 7, 1);
    };
    
    // ADR 0009's full ingest is one query over every competition-season,
    // and it is the DESIGNATED RUN: the base rate, h and the champion
    // log-loss were all measured on it, and the gates below only compare
    // against a run of that same population.
    //
    // SLICES stays as the fast regression path - 2 seconds against 12 -
    // and because reading one league by hand is the leaderboard check no
    // test provides.
    private enum Scope { ALL, SLICES }

    private static final Scope SCOPE = Scope.ALL;


    private static final Path STATSBOMB_DIR = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/statsbomb-open-data/data");
    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");

    // ADR 0009's second file (item 26). Absent today, so the loader attaches
    // nothing and the run is byte-identical; stage 3 creates it with the
    // first real repair, and this same wiring picks it up unchanged.
    private static final Path SIDECAR = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb");

    // ADR 0009's third file, ADR 0011's first use of it. Kept beside the
    // snapshot and the sidecar so one DuckDB connection can attach all three,
    // and out of the repo because it is rebuilt, not versioned.
    private static final Path RESULTS = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb");


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
        Set<Long> leagueMatches = new HashSet<>();
        List<HeldAppearance> held = new ArrayList<>();
        switch (SPINE) {
            case STATSBOMB -> loadStatsBomb(matches, replays);
            case TRANSFERMARKT -> loadTransfermarkt(matches, replays, leagueMatches, held);
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
        long leagueHome = 0, leagueAway = 0;
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            if (m.homeSide() == Match.HomeSide.NEITHER) {
                continue;
            }
            long homeTeamId = m.homeSide() == Match.HomeSide.HOME
                ? m.home().id()
                : m.away().id();
            boolean league = leagueMatches.contains(m.matchId());
            for (MatchEvent e : replays.get(i)) {
                if (e instanceof MatchEvent.Goal goal) {
                    boolean byHomeSide = goal.scoringTeam().id() == homeTeamId;
                    if (byHomeSide) {
                        homeGoals++;
                    } else {
                        awayGoals++;
                    }
                    if (league) {
                        if (byHomeSide) {
                            leagueHome++;
                        } else {
                            leagueAway++;
                        }
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
            + " -> anchor: h =~ ln(ratio)/k : %.2f rating points%n",
            homeGoals, homeGoals + awayGoals,
            100.0 * homeGoals / (homeGoals + awayGoals),
            Math.log((double) homeGoals / awayGoals) / LINK_GAINS[0]);
        // The pooled share mixes populations that are not comparable. In a
        // league every club hosts every other club exactly once, so both
        // sides are drawn from the same distribution and the share reads
        // home advantage cleanly. In a cup the weaker club usually hosts,
        // which pushes the share below 50% and would read as a home
        // DISadvantage. Leagues are the anchor a tuned h is judged against.
        if (leagueHome > 0 && leagueAway > 0) {
            System.out.printf(Locale.US,
                "  domestic leagues only: %d of %d (%.1f%%) -> h =~ %.2f  <- the fair anchor%n",
                leagueHome, leagueHome + leagueAway,
                100.0 * leagueHome / (leagueHome + leagueAway),
                Math.log((double) leagueHome / leagueAway) / LINK_GAINS[0]);
        }
        System.out.println();

        // Grid search: prequential mean log-loss per (k, K). 0.6931 = ln 2 is
        // the know-nothing baseline; lower is better.
        System.out.printf("%8s %8s %8s %8s %8s %8s %10s %10s%n",
            "k", "K0", "H", "floor", "home", "field", "logloss", "whole");

        double bestGain = 0, bestK0 = 0, bestH = 0, bestFloor = 0, bestHome = 0, bestLoss = Double.MAX_VALUE;
        boolean bestFieldOnly = false;
        for (double gain : LINK_GAINS) {
            for (double k0 : K0S) {
                for (double h : HALVING_MINUTES) {
                    for (double floor : FLOOR_FRACTIONS) {
                        for (double home : HOME_ADVANTAGES) {
                            for (boolean fieldOnly : FIELD_PLAYERS_ONLY ) {
                                ScoringWindow window = new ScoringWindow();
                                replay(matches, replays, gain, home, fieldOnly,
                                    new SmoothFadeSchedule(k0, h, floor), window, MatchObserver.NONE);
                                double loss = window.windowed.meanLogLoss();
                                double whole = window.whole.meanLogLoss();
                                System.out.printf(Locale.US, "%8.2f %8.2f %8.0f %8.2f %8.2f %8s %10.4f %10.4f%n",
                                    gain, k0, h, floor, home, fieldOnly ? "yes" : "no", loss, whole);
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
        // Both gates compare against numbers measured on a POPULATION, so
        // they mean something only when this run IS that population: the
        // whole StatsBomb corpus, or the whole Transfermarkt spine. On a
        // slice they would hold 547 matches against 80,471 and print a
        // failure, which is how a real failure eventually gets ignored.
        //
        // The venue-blind baseline is the h = 0.0 cell of the same grid -
        // same population, same window, same replay - which is the only
        // reason it is comparable at all.
        if (SPINE == Spine.STATSBOMB || SCOPE == Scope.ALL) {
            System.out.printf(Locale.US,
                "Ship gate (ADR 0008): %.4f vs %.4f venue-blind baseline -> %s%n%n",
                bestLoss, VENUE_BLIND_BASELINE,
                bestLoss < VENUE_BLIND_BASELINE ? "strictly better" : "NOT strictly better - do not ship");
            System.out.printf(Locale.US,
                "Item 11 gate: best %.4f (field-players-only: %s) vs %.4f all-players champion -> %s%n%n",
                bestLoss, bestFieldOnly ? "yes": "no", CHAMPION,
                bestFieldOnly && bestLoss < CHAMPION
                    ? "adop field-player-only-Strength" : "keep Goalkeepers in Strength");
        }

        // Final replay with the winning knobs; the reports come from this one,
        // and so does the rating history. ADR 0009: history belongs to ONE
        // designated run, never to the grid. The run_id carries the knobs,
        // which is what turns a spine-versus-spine comparison into a join.
        String runId = String.format(Locale.ROOT, "%s-%s-k%.2f-K0%.2f-H%.0f-f%.2f-h%.2f",
            SPINE, SCOPE, bestGain, bestK0, bestH, bestFloor, bestHome);
        Map<Long, PlayerTally> tallies;
        try (RatingHistoryWriter history = new RatingHistoryWriter(RESULTS, runId)) {
            tallies = replay(matches, replays, bestGain, bestHome, bestFieldOnly,
                new SmoothFadeSchedule(bestK0, bestH, bestFloor), new ScoringWindow(), history);
            System.out.printf(Locale.US, "%nRating history: %,d rows -> %s (run %s)%n",
                history.rows(), RESULTS.toAbsolutePath(), runId);
        }

        // The worklist is Transfermarkt's (its gate produced it). Written after
        // the history block so the two never hold the results file at once.
        if (SPINE == Spine.TRANSFERMARKT) {
            long heldRows = HeldAppearanceWriter.write(RESULTS, runId, held);
            System.out.printf(Locale.US, "Held worklist: %,d rows -> %s%n",
                heldRows, RESULTS.toAbsolutePath());
        }

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
    private static void loadTransfermarkt(List<Match> matches, List<List<MatchEvent>> replays,
        Set<Long> leagueMatches, List<HeldAppearance> held) throws Exception {

        try (TransfermarktLoader loader = new TransfermarktLoader(SNAPSHOT, SIDECAR)) {
            List<Match> all = new ArrayList<>();
            switch (SCOPE) {
                case ALL -> {
                    all.addAll(loader.loadMatches());
                    System.out.printf("all competitions: %d matches%n", all.size());
                }
                case SLICES -> {
                    for (Slice slice : SLICES) {
                        List<Match> loaded = loader.loadMatches(slice.competitionId(), slice.season());
                        System.out.printf("%-5s %-6s %4d matches%n",
                            slice.competitionId(), slice.season(), loaded.size());
                        all.addAll(loaded);
                    }
                }
            }
            leagueMatches.addAll(loader.leagueMatches());
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
                    skipped.merge(e.reason(), 1, Integer::sum);
                }
            }
            System.out.printf("%d of %d matches replay, %d events dropped.%n",
                matches.size(), all.size(), loader.droppedEvents());
            skipped.forEach((reason, count) ->
                System.out.printf("  skipped %4d x %s%n", count, reason));

            // The certain-tier worklist, reconciled against the skip report
            // above: these match counts must equal its three lineup-bearing
            // lines (XI is not 11 / no GK / two GKs), because they are the same
            // throw counted two ways.
            held.addAll(loader.heldAppearances());
            Map<String, Set<Long>> heldMatches = new TreeMap<>();
            for (HeldAppearance h : held) {
                heldMatches.computeIfAbsent(h.reason(), r -> new HashSet<>()).add(h.gameId());
            }
            int heldMatchTotal = heldMatches.values().stream().mapToInt(Set::size).sum();
            System.out.printf("held worklist: %d player-rows over %d matches%n",
                held.size(), heldMatchTotal);
            heldMatches.forEach((reason, ids) ->
                System.out.printf("  %4d matches x %s%n", ids.size(), reason));

            // The venue verdict, which no test can eyeball for you.
            Map<Match.HomeSide, Integer> venues = new TreeMap<>();
            for (Match m : matches) {
                venues.merge(m.homeSide(), 1, Integer::sum);
            }
            System.out.println("  venue: " + venues);
        }
    }

    // Hears every goal's expected probability and grades it twice: once
    // over the whole replay, once over the scoring window only. Reporting
    // both keeps the choice of window visible instead of buried.
    private static final class ScoringWindow implements DoubleConsumer {
        private final PredictionQuality windowed = new PredictionQuality();
        private final PredictionQuality whole = new PredictionQuality();
        private boolean open = true;

        void openFrom(LocalDate matchDate) {
            open = !matchDate.isBefore(SCORING_FROM);
        }

        @Override
        public void accept(double p) {
            whole.observe(p);
            if (open) {
                windowed.observe(p);
            }
        }
    }

    // One full chronological replay of all matches with the given knobs;
    // returns the resulting tallies. The window hears every goal's expected
    // P and decides which of them count toward the score - it changes no
    // rating, and the replay order is untouched.
    private static Map<Long, PlayerTally> replay(List<Match> matches, List<List<MatchEvent>> replays,
        double linkGain, double homeAdvantage, boolean fieldPlayersOnly, UpdateSchedule schedule,
        ScoringWindow window, MatchObserver observer) {

        MatchProcessor processor = new MatchProcessor(
            new TimeIntegratedResidual(BASE_RATE, linkGain, homeAdvantage, fieldPlayersOnly, window), schedule);
        Map<Long, PlayerTally> tallies = new HashMap<>();
        for (int i = 0; i < replays.size(); i++) {
            window.openFrom(matches.get(i).date());
            observer.startMatch(matches.get(i).matchId(), matches.get(i).date());
            processor.process(replays.get(i), tallies, observer);
        }
        return tallies;
    }

}
