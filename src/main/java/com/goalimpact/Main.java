package com.goalimpact;

import com.goalimpact.credit.TimeIntegratedResidual;
import com.goalimpact.data.AppearedPlayer;
import com.goalimpact.data.DataFiles;
import com.goalimpact.data.DataLoader;
import com.goalimpact.data.HeldAppearance;
import com.goalimpact.data.HeldMatch;
import com.goalimpact.data.MaybePlayer;
import com.goalimpact.data.TransfermarktLoader;
import com.goalimpact.data.UnusableMatchException;
import com.goalimpact.engine.AgeingCurve;
import com.goalimpact.engine.ClubPools;
import com.goalimpact.engine.MatchObserver;
import com.goalimpact.engine.MatchProcessor;
import com.goalimpact.engine.RatingSeed;
import com.goalimpact.engine.PlayerTally;
import com.goalimpact.engine.PredictionQuality;
import com.goalimpact.engine.SmoothFadeSchedule;
import com.goalimpact.engine.UpdateSchedule;
import com.goalimpact.model.CompetitionSeason;
import com.goalimpact.model.Match;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;
import com.goalimpact.report.CsvWriter;
import com.goalimpact.report.HeldAppearanceWriter;
import com.goalimpact.report.HeldMatchWriter;
import com.goalimpact.report.Leaderboard;
import com.goalimpact.report.MissingMatchWriter;
import com.goalimpact.report.RatingHistoryWriter;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
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

    // Item 16: rating points a player enters the run below average at, when
    // the club he is first seen for is one this run never prices - one that
    // plays no league football anywhere in it (see ClubPools). 0.0 is the
    // status quo, where every debutant enters at exactly world-average.
    //
    // MEASURED 2026-08-01, not tuned, and pinned and dated like BASE_RATE and
    // h. Unpriced sides underperform expectation by 0.3568 goals per 90 -
    // their opponents collect +0.3196 residual per 90 against them where the
    // rest of the run runs -0.0373 - and that shortfall inverted through the
    // link function is
    //     d = (2 / k) * asinh(0.3568 / (180 * BASE_RATE)) = 2.58 rating points.
    // Derivation and its bias statistics: scripts/unpriced-seed.sql, over the
    // designated run of 85,050 matches, 2012-07-09 to 2026-07-06. 2,074 of
    // 2,867 clubs are unpriced there, 11.4% of appearances, and 58.7% of all
    // debuts happen at one - which is what the seed can actually reach, since
    // it prices a player once and never again.
    //
    // GATE (#39), against the seed = 0 cell of the same grid: bridge log-loss
    // 0.6341 vs 0.6457, whole-population 0.6488 vs 0.6510. Both arms pass, so
    // the seed ships.
    //
    // The check sweep does NOT turn over at the measured value, and that is
    // recorded rather than acted on. Bridge / whole at 0, 2.58, 8, 15, 30:
    //   0.6457/0.6510, 0.6341/0.6488, 0.6169/0.6458, 0.6081/0.6448, 0.6269/0.6515.
    // A seed near 15 scores better on both arms. It is not adopted, for two
    // reasons that are the same reason twice: #39 fixed this constant as
    // DERIVED, so the winner of a sweep is not evidence about it; and 15
    // points is over two population standard deviations, which is no longer
    // pricing a minnow honestly but calibrating whole pools - item 9's fix,
    // which #39 sequenced after this one and which arrives with its own gate.
    // Taking it here would spend item 9's evidence under item 16's name and
    // leave nothing to measure it with.
    //
    // Which is why the shipped value and the swept values are two different
    // fields, and not one array with a winner. UNPRICED_SEED is what the
    // DESIGNATED RUN uses, full stop; UNPRICED_SEEDS is the grid's check, and
    // nothing it reports can change what ships. Every other knob here is
    // genuinely tuned and a grid winner is the right answer for it - for this
    // one it is not, and a comment saying so would only hold until the next
    // person widened the sweep.
    private static final double UNPRICED_SEED = 2.58;

    // The check, and the gate's own baseline. 0.0 must stay in it: the gate is
    // two cells of one grid differing in exactly one thing (ADR 0014), so
    // dropping the mechanism-off cell would make the decision unreproducible
    // without editing source - the same reason FIELD_PLAYERS_ONLY keeps the
    // arm that lost.
    private static final double[] UNPRICED_SEEDS = {0.0, UNPRICED_SEED};

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
    // regression check that the shipped model still scores what it did. A
    // champion belongs to a POPULATION (#43), so each line states one.
    //   StatsBomb     2026-07-16: 0.6259 (k .10, K0 1.0, H 4000, h 2.5),
    //     over the whole men's corpus.
    //   Transfermarkt 2026-07-22: 0.6502 (k .10, K0 1.0, H 4000, h 2.0),
    //     over 80,471 matches, 2013-07-02 to 2026-07-06; 0.6508 whole replay.
    //   Transfermarkt 2026-08-01: 0.6503 (same knobs) over 85,050 matches,
    //     2012-07-09 to 2026-07-06; 0.6510 whole replay. Not an experiment
    //     result - item 30 stage 3 is widening the spine underneath the old
    //     number, and #43's rule is that a champion belongs to a population,
    //     so the reigning model is simply re-run for a fresh baseline. The
    //     0.6502 line is kept because it is the last number belonging to the
    //     narrower spine, not because it is comparable to this one.
    //   Transfermarkt 2026-08-01: 0.6481 (same knobs, unpriced seed 2.58),
    //     same 85,050 matches; 0.6488 whole replay. Item 16, and this one IS
    //     an experiment result - gated on bridge matches against the line
    //     above, which is its same-run baseline (ADR 0014).
    //
    // BOTH 2026-08-01 NUMBERS ARE MID-FLIGHT. Item 30 stage 3 was still
    // scraping when they were measured - 2015 lineups in progress, seasons
    // 2022 down to 2012, with more scraping after that - so 85,050 is a
    // snapshot of a moving spine and not the finish line ADR 0013 pins. Every
    // one of these lines is expected to be superseded by a re-measure on the
    // wide spine; that is ADR 0014 rules 3 and 4, working as intended, and not
    // a sign that anything here is wrong.
    private static final double CHAMPION = switch (SPINE) {
        case STATSBOMB -> 0.6259;
        case TRANSFERMARKT -> 0.6481;
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
        List<HeldAppearance> held = new ArrayList<>();
        List<AppearedPlayer> appeared = new ArrayList<>();
        List<MaybePlayer> maybe = new ArrayList<>();
        List<HeldMatch> heldMatches = new ArrayList<>();
        // ADR 0016: every man on the pitch needs an age now, so the dates of
        // birth are loaded with the fixtures rather than looked up at
        // reporting time. StatsBomb's files carry none, which the curve reads
        // as "unknown age" for the whole population - correct, and at the
        // pinned flat table it costs nothing either way.
        Map<Long, LocalDate> birthDates = new HashMap<>();
        switch (SPINE) {
            case STATSBOMB -> loadStatsBomb(matches, replays);
            case TRANSFERMARKT -> loadTransfermarkt(
                matches, replays, held, appeared, maybe, heldMatches, birthDates);
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
            boolean league = m.competition().isLeague();
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

        // Item 16: which clubs this run never prices, and how much of the
        // replay they account for. Derived from the run's own fixture list -
        // a fact about COVERAGE, not about results - and reported before the
        // grid because it describes the population, not the model. The
        // per-player breakdown ADR 0009 asked for is the same verdict read
        // off rating_history in scripts/model-bias-diagnostics.sql; this is
        // the run-level reconciliation it must agree with.
        ClubPools pools = ClubPools.of(matches);
        Appearances census = census(pools, birthDates, replays);
        long bridgeMatches = matches.stream().filter(pools::isBridge).count();
        System.out.printf(Locale.US,
            "Unpriced clubs (item 16): %,d of %,d clubs play no league football in this run"
            + " (%.1f%%), %,d of %,d appearances (%.1f%%)%n",
            pools.unpricedClubs(), pools.clubs(),
            100.0 * pools.unpricedClubs() / pools.clubs(),
            census.unpriced(), census.all(),
            100.0 * census.unpriced() / census.all());
        System.out.printf(Locale.US,
            "Bridge matches (item 39 gate population): %,d of %,d (%.1f%%)%n%n",
            bridgeMatches, matches.size(), 100.0 * bridgeMatches / matches.size());

        // ADR 0016: how much of the replay the ageing curve can actually age.
        // Everyone else is charged the unknown-age penalty, so this is the
        // exposure of that one constant - reported before the grid, like the
        // unpriced census above, because it describes the population.
        AgeingCurve ageing = AgeingCurve.pinned(birthDates);
        System.out.printf(Locale.US,
            "Dates of birth (ADR 0016): %,d of %,d men on the pitch (%.1f%%),"
            + " %,d of %,d appearances (%.1f%%)%n%n",
            census.datedMen(), census.men(),
            100.0 * census.datedMen() / Math.max(1, census.men()),
            census.datedAppearances(), census.all(),
            100.0 * census.datedAppearances() / Math.max(1, census.all()));

        // Grid search: prequential mean log-loss per (k, K). 0.6931 = ln 2 is
        // the know-nothing baseline; lower is better. "bridge" is the same
        // windowed loss over bridge matches only - reported for every cell,
        // and the primary arm of the item 16 gate below.
        System.out.printf("%8s %8s %8s %8s %8s %8s %8s %10s %10s %10s%n",
            "k", "K0", "H", "floor", "home", "field", "seed", "logloss", "whole", "bridge");

        double bestGain = 0, bestK0 = 0, bestH = 0, bestFloor = 0, bestHome = 0, bestLoss = Double.MAX_VALUE;
        boolean bestFieldOnly = false;
        // The seed is NOT among the knobs a winner is picked from - it is
        // measured, and UNPRICED_SEED alone decides what the designated run
        // uses. The grid only reports it, keyed here so the item 16 gate can
        // compare two cells of THIS grid rather than a pinned historical
        // number (#39): bridge log-loss has no champion on any population, and
        // a bias fix must be judged against a baseline that ran on the same
        // spine.
        Map<Double, Scores> bySeed = new TreeMap<>();
        for (double gain : LINK_GAINS) {
            for (double k0 : K0S) {
                for (double h : HALVING_MINUTES) {
                    for (double floor : FLOOR_FRACTIONS) {
                        for (double home : HOME_ADVANTAGES) {
                            for (boolean fieldOnly : FIELD_PLAYERS_ONLY ) {
                              for (double seed : UNPRICED_SEEDS) {
                                ScoringWindow window = new ScoringWindow(pools);
                                replay(matches, replays, gain, home, fieldOnly,
                                    new SmoothFadeSchedule(k0, h, floor),
                                    RatingSeed.unpricedBelowAverage(pools, seed),
                                    ageing, window, MatchObserver.NONE);
                                double loss = window.windowed.meanLogLoss();
                                double whole = window.whole.meanLogLoss();
                                double bridge = window.bridged.meanLogLoss();
                                System.out.printf(Locale.US,
                                    "%8.2f %8.2f %8.0f %8.2f %8.2f %8s %8.2f %10.4f %10.4f %10.4f%n",
                                    gain, k0, h, floor, home, fieldOnly ? "yes" : "no", seed,
                                    loss, whole, bridge);
                                bySeed.put(seed, new Scores(loss, whole, bridge));
                                // Only cells at the shipped seed are eligible
                                // to win, so widening the check sweep can
                                // never quietly change which knobs ship.
                                if (seed == UNPRICED_SEED && loss < bestLoss) {
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

        // Item 16's gate, settled in #39, and it is a change to house
        // practice. PRIMARY: log-loss on bridge matches only, which must
        // STRICTLY improve. GUARD: whole-population log-loss must not worsen
        // at four decimals. Both arms against the seed = 0 cell of this very
        // grid - a same-run baseline, like VENUE_BLIND_BASELINE and for the
        // same reason, and necessary anyway because bridge log-loss has no
        // champion on any population.
        //
        // The gate needs two cells that differ only in the seed, so it prints
        // only when the seed is the one thing this grid sweeps.
        boolean seedSweepOnly = LINK_GAINS.length == 1 && K0S.length == 1
            && HALVING_MINUTES.length == 1 && FLOOR_FRACTIONS.length == 1
            && HOME_ADVANTAGES.length == 1 && FIELD_PLAYERS_ONLY.length == 1;
        Scores unseeded = bySeed.get(0.0);
        if (seedSweepOnly && unseeded != null && bySeed.size() > 1
            && (SPINE == Spine.STATSBOMB || SCOPE == Scope.ALL)) {

            for (Map.Entry<Double, Scores> cell : bySeed.entrySet()) {
                if (cell.getKey() == 0.0) {
                    continue;
                }
                Scores seeded = cell.getValue();
                // BOTH arms read at four decimals, which is what the house
                // rule says and all it can support: a move in the fifth
                // decimal is noise, and noise must not pass as an improvement
                // any more than it may count as a regression.
                boolean primary = round4(seeded.bridge()) < round4(unseeded.bridge());
                boolean guard = round4(seeded.whole()) <= round4(unseeded.whole());
                System.out.printf(Locale.US,
                    "Item 16 gate, seed %.2f: bridge %.4f vs %.4f baseline -> %s;"
                    + " whole %.4f vs %.4f -> %s => %s%n",
                    cell.getKey(), seeded.bridge(), unseeded.bridge(),
                    primary ? "improves" : "NOT strictly better",
                    seeded.whole(), unseeded.whole(), guard ? "no worse" : "WORSENS",
                    primary && guard ? "ADOPT the unpriced seed" : "keep every debutant at average");
            }
            System.out.println();
        }

        // Final replay with the winning knobs; the reports come from this one,
        // and so does the rating history. ADR 0009: history belongs to ONE
        // designated run, never to the grid. The run_id carries the knobs,
        // which is what turns a spine-versus-spine comparison into a join.
        // Read into a local so the suffix test below stays a real test: as a
        // compile-time constant the shipped seed folds the mechanism-off arm
        // away, and that arm is the promise that pinning the seed back to 0.0
        // restores the run id this project has always used.
        double designatedSeed = UNPRICED_SEED;
        String runId = String.format(Locale.ROOT, "%s-%s-k%.2f-K0%.2f-H%.0f-f%.2f-h%.2f",
            SPINE, SCOPE, bestGain, bestK0, bestH, bestFloor, bestHome)
            // Only when it is on, so a run at the status quo keeps the run id
            // it has always had and a seeded run is never mistaken for one.
            + (designatedSeed == 0.0 ? "" : String.format(Locale.ROOT, "-s%.2f", designatedSeed));
        Map<Long, PlayerTally> tallies;
        try (RatingHistoryWriter history = new RatingHistoryWriter(DataFiles.RESULTS, runId)) {
            tallies = replay(matches, replays, bestGain, bestHome, bestFieldOnly,
                new SmoothFadeSchedule(bestK0, bestH, bestFloor),
                RatingSeed.unpricedBelowAverage(pools, UNPRICED_SEED),
                ageing, new ScoringWindow(pools), history);
            System.out.printf(Locale.US, "%nRating history: %,d rows -> %s (run %s)%n",
                history.rows(), DataFiles.RESULTS.toAbsolutePath(), runId);
        }

        // The worklist is Transfermarkt's (its gate produced it). Written after
        // the history block so the two never hold the results file at once.
        if (SPINE == Spine.TRANSFERMARKT) {
            long heldRows = HeldAppearanceWriter.write(DataFiles.RESULTS, runId, held);
            System.out.printf(Locale.US, "Held worklist: %,d rows -> %s%n",
                heldRows, DataFiles.RESULTS.toAbsolutePath());
            // The two lower rungs (item 26, stage 4a), written after the held
            // block so no two writers hold the results file at once.
            MissingMatchWriter.write(DataFiles.RESULTS, runId, appeared, maybe);
            System.out.printf(Locale.US,
                "Missing-match tiers: %,d appeared + %,d maybe rows -> %s%n",
                appeared.size(), maybe.size(), DataFiles.RESULTS.toAbsolutePath());
            // The match-level spine (item 29), last of the four and written the
            // same way: one writer at a time on the results file.
            long spineRows = HeldMatchWriter.write(DataFiles.RESULTS, runId, heldMatches);
            System.out.printf(Locale.US, "Held matches: %,d rows -> %s%n",
                spineRows, DataFiles.RESULTS.toAbsolutePath());
        }

        new Leaderboard().print(tallies.values(), 20);

        // Overridable for the same reason DataFiles' paths are, and never for
        // any other: a diagnostic run against a rebuilt snapshot must not
        // overwrite the designated run's leaderboard.
        Path csv = Path.of(System.getProperty("goalimpact.csv", "goalimpact.csv"));
        new CsvWriter().write(csv, tallies.values());
        System.out.println();
        System.out.println("Full results written to " + csv.toAbsolutePath());

        reportPeakHeap();
    }

    // Item 30, decision 8: every important number in this project is pinned and
    // dated except the memory the run is allowed, which is silently a quarter of
    // whatever machine it runs on. This is the measurement that sizes an explicit
    // -Xmx before the spine widens; peak comes from the pools rather than from
    // Runtime, which would only report the heap as it happens to stand at the end.
    private static void reportPeakHeap() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        System.out.printf(Locale.US, "Peak heap: %,d MiB used of %,d MiB ceiling%n",
            peak / (1024 * 1024), Runtime.getRuntime().maxMemory() / (1024 * 1024));
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
        List<HeldAppearance> held,
        List<AppearedPlayer> appeared, List<MaybePlayer> maybe,
        List<HeldMatch> heldMatches, Map<Long, LocalDate> birthDates) throws Exception {

        try (TransfermarktLoader loader = new TransfermarktLoader(DataFiles.SNAPSHOT, DataFiles.SIDECAR)) {
            birthDates.putAll(loader.birthDates());
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
            if (loader.releasedCount() > 0) {
                System.out.printf("  sidecar: %d released match(es)%n", loader.releasedCount());
            }
            skipped.forEach((reason, count) ->
                System.out.printf("  skipped %4d x %s%n", count, reason));

            // The certain-tier worklist, reconciled against the skip report
            // above: these match counts must equal its three lineup-bearing
            // lines (XI is not 11 / no GK / two GKs), because they are the same
            // throw counted two ways.
            held.addAll(loader.heldAppearances());
            Map<String, Set<Long>> certainByReason = new TreeMap<>();
            for (HeldAppearance h : held) {
                certainByReason.computeIfAbsent(h.reason(), r -> new HashSet<>()).add(h.gameId());
            }
            int heldMatchTotal = certainByReason.values().stream().mapToInt(Set::size).sum();
            System.out.printf("held worklist: %d player-rows over %d matches%n",
                held.size(), heldMatchTotal);
            certainByReason.forEach((reason, ids) ->
                System.out.printf("  %4d matches x %s%n", ids.size(), reason));

            // The appeared + maybe tiers (item 26, stage 4a). They PARTITION the
            // "no lineups" Held matches: every one is appeared (its players are
            // named in appearances) xor maybe (nothing at all - candidates only).
            // The split must reconcile to the skip report's "no lineups" line.
            appeared.addAll(loader.appearedPlayers());
            maybe.addAll(loader.maybePlayers());
            Set<Long> appearedGames = new HashSet<>();
            for (AppearedPlayer a : appeared) {
                appearedGames.add(a.gameId());
            }
            Set<Long> maybeGames = new HashSet<>();
            for (MaybePlayer m : maybe) {
                maybeGames.add(m.gameId());
            }
            int appearedMatches = appearedGames.size();
            int maybeMatches = loader.heldNoLineupCount() - appearedMatches;
            System.out.printf("appeared tier: %,d rows over %d matches%n",
                appeared.size(), appearedMatches);
            System.out.printf(
                "maybe tier: %,d rows over %d of %d matches (%d have no club game within a month)%n",
                maybe.size(), maybeGames.size(), maybeMatches, maybeMatches - maybeGames.size());
            System.out.printf("  no-lineup partition: %d = %d appeared + %d maybe%n",
                loader.heldNoLineupCount(), appearedMatches, maybeMatches);

            // The match-level spine (item 29, slice 1), reconciled against the
            // skip report above: one row per Held match, whatever the reason, so
            // the two totals are the same verdict counted twice. The reachability
            // line is the measurement item 29 is named after, made permanent -
            // a row without both club ids would be a match no club view could
            // reach, and there must never be one.
            heldMatches.addAll(loader.heldMatches());
            Map<String, Integer> spineByReason = new TreeMap<>();
            Map<String, Integer> spineBySource = new TreeMap<>();
            for (HeldMatch h : heldMatches) {
                spineByReason.merge(h.reason(), 1, Integer::sum);
                spineBySource.merge(h.repairSource().name(), 1, Integer::sum);
            }
            System.out.printf("held matches: %,d rows%n", heldMatches.size());
            spineByReason.forEach((reason, n) ->
                System.out.printf("  %5d x %s%n", n, reason));
            spineBySource.forEach((source, n) ->
                System.out.printf("  %5d repair source %s%n", n, source));
            long unreachable = heldMatches.stream()
                .filter(h -> h.homeClubId() == 0 || h.awayClubId() == 0).count();
            System.out.printf("  reachable by club: %,d of %,d (%d unreachable)%n",
                heldMatches.size() - unreachable, heldMatches.size(), unreachable);

            // The venue verdict, which no test can eyeball for you.
            Map<Match.HomeSide, Integer> venues = new TreeMap<>();
            for (Match m : matches) {
                venues.merge(m.homeSide(), 1, Integer::sum);
            }
            System.out.println("  venue: " + venues);
        }
    }

    // How much of the replay is played by men at clubs the run never prices -
    // the exposure behind item 16's seed, counted the way the engine counts a
    // player onto the pitch: a starter once, a substitute once when he comes
    // on. Read off the events rather than the tallies because a tally holds a
    // career, and the question here is about appearances.
    // ADR 0016 asks the same walk a second question - how many of those men the
    // ageing curve can actually age - so it is answered on the same pass rather
    // than on one of its own. Dates of birth are counted twice, once per man and
    // once per appearance, and the two differ sharply: the players without one
    // are the ones who play least, which is the same fact ADR 0011 saw at the
    // chart's threshold (15 of 17,030 past 1,000 minutes).
    private record Appearances(long unpriced, long all,
        long datedMen, long men, long datedAppearances) {
    }

    // One grid cell's three log-losses: the windowed score the grid picks on,
    // the whole replay beside it (ADR 0010), and the same window over bridge
    // matches only (ADR 0014).
    private record Scores(double windowed, double whole, double bridge) {
    }

    // Four decimals is the resolution every gate in this project is stated at,
    // and comparisons happen at it rather than below it.
    private static long round4(double logLoss) {
        return Math.round(logLoss * 10000);
    }

    private static Appearances census(ClubPools pools, Map<Long, LocalDate> birthDates,
        List<List<MatchEvent>> replays) {

        long unpriced = 0, all = 0, datedAppearances = 0;
        Set<Long> seen = new HashSet<>();
        Set<Long> dated = new HashSet<>();
        for (List<MatchEvent> events : replays) {
            for (MatchEvent e : events) {
                Team side;
                List<Player> men;
                switch (e) {
                    case MatchEvent.StartingXI s -> {
                        side = s.team();
                        men = s.players();
                    }
                    case MatchEvent.Substitution sub -> {
                        side = sub.team();
                        men = List.of(sub.playerOn());
                    }
                    default -> {
                        continue;
                    }
                }
                all += men.size();
                if (pools.unpriced(side.id())) {
                    unpriced += men.size();
                }
                for (Player p : men) {
                    seen.add(p.id());
                    if (birthDates.containsKey(p.id())) {
                        dated.add(p.id());
                        datedAppearances++;
                    }
                }
            }
        }
        return new Appearances(unpriced, all, dated.size(), seen.size(), datedAppearances);
    }

    // Hears every goal's expected probability and grades it twice: once
    // over the whole replay, once over the scoring window only. Reporting
    // both keeps the choice of window visible instead of buried.
    private static final class ScoringWindow implements DoubleConsumer {
        private final ClubPools pools;
        private final PredictionQuality windowed = new PredictionQuality();
        private final PredictionQuality whole = new PredictionQuality();
        // Item 39's scoped gate: the same window, restricted to matches
        // between clubs from different leagues. A whole-league mispricing
        // moves both sides of a DOMESTIC match equally, so the gap the
        // prediction reads barely moves and a correct bias fix scores as a
        // tie - which the house rule would then reject. Bridges are where
        // such a fix can show up at all.
        private final PredictionQuality bridged = new PredictionQuality();
        private boolean open = true;
        private boolean bridge = false;

        ScoringWindow(ClubPools pools) {
            this.pools = pools;
        }

        void startMatch(Match match) {
            open = !match.date().isBefore(SCORING_FROM);
            bridge = pools.isBridge(match);
        }

        @Override
        public void accept(double p) {
            whole.observe(p);
            if (open) {
                windowed.observe(p);
                if (bridge) {
                    bridged.observe(p);
                }
            }
        }
    }

    // One full chronological replay of all matches with the given knobs;
    // returns the resulting tallies. The window hears every goal's expected
    // P and decides which of them count toward the score - it changes no
    // rating, and the replay order is untouched.
    private static Map<Long, PlayerTally> replay(List<Match> matches, List<List<MatchEvent>> replays,
        double linkGain, double homeAdvantage, boolean fieldPlayersOnly, UpdateSchedule schedule,
        RatingSeed seed, AgeingCurve ageing, ScoringWindow window, MatchObserver observer) {

        MatchProcessor processor = new MatchProcessor(
            new TimeIntegratedResidual(BASE_RATE, linkGain, homeAdvantage, fieldPlayersOnly, window),
            schedule, seed);
        Map<Long, PlayerTally> tallies = new HashMap<>();
        for (int i = 0; i < replays.size(); i++) {
            window.startMatch(matches.get(i));
            observer.startMatch(matches.get(i).matchId(), matches.get(i).date());
            // ADR 0016: the age term is bound to this match's kickoff here, at
            // the one place that has both the curve and the date, so nothing
            // downstream carries a calendar.
            processor.process(replays.get(i), tallies,
                ageing.at(matches.get(i).date()), observer);
        }
        return tallies;
    }

}
