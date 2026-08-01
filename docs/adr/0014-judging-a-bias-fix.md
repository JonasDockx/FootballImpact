# A bias fix is judged on a scoped population, against a same-run baseline

A change that corrects a **bias** — a mispricing concentrated in one part of the
population — is gated on **bridge matches only**: matches whose two clubs share
no league. It must *strictly improve* bridge log-loss, and must *not worsen*
whole-population log-loss at four decimals. Both arms are read from a
**same-run baseline**: the same grid, the same spine, the mechanism switched
off, never a pinned historical champion.

Four rules follow from that and are stated here once, because they govern every
bias fix in the sequence #39 laid out — item 16 (landed), item 9, item 42 —
and the widening of ADR 0013 that runs across them.

## Why the house rule had to change

The standing rule is that an experiment must beat the current champion
whole-population log-loss at four decimals, and that a tie keeps the status quo.
For a bias fix that rule **rejects correct work**.

A whole-league mispricing moves *both* sides of a domestic match by the same
amount. The prediction reads only the strength *gap*, so the gap barely moves,
so the prediction barely moves. Item 16 is the worked example: the fix touches
11.4% of appearances and 13.4% of matches, and on the other 86.6% it is close to
a no-op by construction. Averaged over everything, a real correction lands in
the fourth decimal and is thrown away as a tie.

Bridges are where a pool-level mispricing is *visible*, because they are the
only matches whose gap spans two pools. Item 16's measured result makes the size
of the difference plain: **bridge 0.6457 → 0.6341** against **whole 0.6510 →
0.6488**. The scoped arm moves five times as far. Scoping is not a lower bar; it
is aiming the measurement at the thing being changed.

## Decisions

**1. The gate population is scoped, and the guard is not.** Primary: bridge
log-loss, strictly better. Guard: whole-population log-loss, not worse at four
decimals. The guard is what stops a fix buying its bridges at everyone else's
expense; without it, scoping *would* be a lower bar.

**2. The baseline is same-run, never a pinned champion.** Bridge log-loss has no
champion on any population, so there is nothing historical to compare to — but
the deeper reason is the one `VENUE_BLIND_BASELINE` already stands on: the
mechanism-off cell of the same grid is the only number that differs from the
mechanism-on cell in exactly one thing.

**3. A champion log-loss belongs to a population.** A widening resets the
record: the reigning model is simply re-run for a fresh baseline, and every
number quoted states its match count and date range. Item 16 landed while this
was live — the spine had already moved from 80,471 to 85,050 matches under item
30 stage 3, so 0.6503 and not the recorded 0.6502 was the number to beat.

**4. After a widening: re-derive the constant, then re-gate the mechanism, one
fix at a time.** A measured constant is measured on a population, so a wider
spine gets a fresh derivation *first*, and only then is the mechanism switched
on and off against a same-run wide-spine baseline. A mechanism that fails while
carrying its own fresh constant is genuinely refuted and is pinned off. Running
the window, the scale, the ageing curve and three bias fixes in one comparison
would confound all six.

## A measured constant is not a swept one

Item 16's seed is *derived* — a residual gap of 0.3568 goals/90, inverted
through the link function — and then pinned and dated, like the base scoring
rate, `h`, `K0` and `H`. A sweep around it is a **check on the arithmetic**,
never the source of the value.

That distinction earned its keep immediately. The check sweep did **not** turn
over at 2.58: bridge log-loss keeps falling to 0.6081 at a seed of 15 before
turning at 30, and a seed of 15 would have won a grid search outright. It was
not adopted. Fifteen rating points is over two population standard deviations —
at that size the seed has stopped pricing a cup minnow honestly and started
calibrating whole pools, which is item 9's fix, sequenced *after* this one and
owed its own gate. Taking the sweep's answer would have spent item 9's evidence
under item 16's name and left nothing to measure it with.

**A sweep that does not turn over is a finding to record, not a value to take.**

## Considered options

- **Keep the whole-population gate (rejected).** Honest and already in place,
  but it cannot resolve the effect: it would have scored item 16 at 0.0022 and a
  smaller fix at nothing, and a rule that rejects correct work teaches the wrong
  lesson every time it fires.
- **Lower the whole-population threshold to five or six decimals (rejected).**
  Keeps one number, but buys resolution by grading noise — and says nothing
  about *where* the change happened, which is the only interesting part of a
  bias fix.
- **Gate on the biased subpopulation itself — matches involving an unpriced club
  (rejected).** Sharper still, and circular: it grades the fix on exactly the
  matches it was fitted to, and every future bias fix would arrive with its own
  bespoke population, making no two comparable. Bridges are defined by the
  *structure* of the run, not by any one mechanism.
- **Report the bias statistics and decide by eye (rejected).** Celtic's 1.20
  domestic-to-European swing against Barcelona's 0.06 is the clearest picture of
  the problem anyone has produced here. It is still a picture. Statistics like
  it are reported and never gate.

## Consequences

- **The grid table carries a `bridge` column** beside `logloss` and `whole`, on
  every cell, so the scoped number is never computed specially for the
  experiment that wants it.
- **The gate prints only when the grid sweeps one mechanism.** It needs two
  cells differing in exactly one thing; anything else and there is no honest
  pairing to print.
- **Bridges are derived, not configured.** `ClubPools` reads them off the run's
  own fixture list — a fact about coverage, not about results, and so not the
  acausal warm-up ADR 0009 rejected.
- **Two national teams are not a bridge here**, though CONTEXT calls such a
  match one. CONTEXT's sense is about the *players*, who do come from different
  leagues; this gate's is about the two *clubs*, and no club-level seed moves
  that prediction. Stated rather than reconciled: 742 matches, and forcing one
  definition to serve both would blur the one that gates.
- **The leagues-only home-advantage anchor moved, from 2.32 to 2.33.** Deriving
  "is this a league?" at the loader boundary corrected four competitions the
  vendor's `competition_type` gets wrong, and the anchor counts league goals, so
  it now counts 95,715 of 171,565 rather than 95,315 of 170,863. Nothing is
  recomputed from it — `h` is a pinned 2.0 and that line is printed, never read
  — so this is a diagnostic reading a slightly better population, not a
  calibration constant moving under anyone.
- **Glossary updated:** *Unpriced club* and *Rating seed* added. *Bridge* and
  *Island* already existed and are unchanged — this ADR uses Bridge, it does not
  redefine it. The seeding half of the decision is
  [ADR 0015](0015-seeding-a-rating-below-average.md); this one is only about how
  such a change is judged.
