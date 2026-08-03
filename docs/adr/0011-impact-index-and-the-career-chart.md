# The Impact index: the career rating on a scale anchored at 100

`Value` — a player's accumulated rating — stays exactly as it is, and is the
quantity plotted. Alongside it, reporting gains the **Impact index**: the same
Value on a population scale where the average player is **100**. A linear
rescale, nothing more. It is a reading of the model, not a change to it: no
residual, no update factor and no replay behaves differently.

The index exists to make one artefact possible — the **career chart**, one
player's line across time, showing what the model thought of him *at that
moment* using only matches already played, against coloured bands for the
quality tiers. This is the shape Goalimpact publishes, and reproducing it is
the point (items 2 and 8, taken as one piece of work).

## Why Value is the level

Value is a signed sum with no ceiling written into it, so the obvious fear is
that it is a *total* — that it grows with career length and therefore draws
minutes played rather than ability. That fear was taken seriously, an entire
design was built on it, and the measurement killed it.

Value **converges**. Tracking one fixed cohort — the 234 players who finished
past 30,000 career minutes — through their own exposure, so that the same
people appear at every step and no survivorship can leak in:

| own exposure reached | mean Value |
|---|---|
| 5,000 | 11.54 |
| 10,000 | 13.54 |
| 15,000 | 14.44 |
| 20,000 | 15.38 |
| 25,000 | 15.79 |
| 30,000 | 16.04 |

It moves **+2.9 over the first 5,000 minutes and +0.66 over the 10,000 minutes
from 20,000 to 30,000**. That is a level being approached, not a total
accruing.

The mechanism is the model's own negative feedback, and it is stronger than it
looks. Expected goal difference is ≈ `0.138 × gap` per 90 and one player is 1
of 11 in `Strength`, so **+1 rating point raises his own side's bar by 0.0125
goals per 90**. Weak per point — but a career is tens of thousands of minutes
long, and the top of the leaderboard sits at +36 rather than +500 because of
it. The brake binds.

An earlier reading of the same data said the opposite — that mean Value rises
with exposure without ever flattening. That table binned *different players* by
career length, so it was measuring survivorship: better players last longer.
Following one cohort forward reverses the conclusion entirely. The distinction
between "average across people at each exposure" and "average within people as
exposure grows" is the whole difference, and it is the same distinction that
will govern item 21's ageing curve.

## Decisions

**The Impact index is Value, linearly rescaled.** Not divided by exposure, not
a rate, not a rank.

**The scale is two constants, measured once and frozen.** Over the designated
run's 25,334 players past 1,000 minutes, Value has mean **1.8374** and standard
deviation **7.1729** (measured 2026-07-22). One standard deviation is **20
index points**:

```
Impact index = 100 + 20 × (Value − 1.8374) / 7.1729
```

These are pinned, dated calibration constants of the same kind as the base
scoring rate and `h`, re-measured only when a large new era or competition
lands — never swept, never re-fitted per run.

**20 points per standard deviation, because Goalimpact's two countable
landmarks agree.** Goalimpact reports roughly 200 players worldwide above 160,
and an all-time best of about 200. Ours, not fitted to either:

| spread | players ≥ 160 | best |
|---|---|---|
| 15 | 21 | 172 |
| 18 | 85 | 187 |
| **20** | **150** | **196** |

20 lands just under both, which is the right side to miss on: this pool is
25,334 eligible players over thirteen years and 65 competitions, smaller than
the worldwide all-time database those landmarks come from.

**The scale never re-centres.** 100 means the same thing in 2014 and in 2026.
Re-centring per season would force every season's average to 100 by
construction, hiding any real change in the level of football and — fatally for
a career chart — measuring a career with a ruler whose length keeps changing.

**Bands are drawn at 100 / 140 / 150 / 170.** *Amended 2026-08-02 (#22): the
chart draws **#47's seven rank steps** as its bands instead — 177.2, 169.3,
161.9, 154.7, 143.2, 133.5, 123.5 — each zone between them shaded a step of one
ordered ramp, plus a line at **100**, which stays because the population average
is the number the index is defined against and the one landmark a rank cannot
express. 140, 150 and 170 are no longer drawn: they were unlabelled round
numbers, and the rank steps sit within a few points of them saying something a
reader can act on. The bands were always a reading aid, never part of the scale,
so this changes no number the model produces.*

**A world rank is drawn beside the index, which does not make the index one.**
The viewer's right edge carries seven pinned rank ticks and the four bands carry
rank glosses (#47), measured once over a stated active pool and dated like every
other calibration constant. That is a reading aid laid alongside the scale — the
index itself is still a linear rescale of Value and still not a rank, which is
what the rejected option below was about.

**A line starts at 1,000 career minutes.** Goalimpact's creators use this
threshold for relevance. It also very nearly closes the date-of-birth gap on
its own: 69,975 of 114,893 players in lineups have no row in `players` at all,
but of the 17,030 players past 1,000 minutes by the vendor's own count, **15**
lack a date of birth. Verified against the written history — 90.3% of its
2,352,025 rows resolve to a date of birth, matching ADR 0009's prediction.

**The index is computed at reporting time, from stored history, in SQL.** It is
a linear function of two constants over rows the run already wrote, so changing
a constant or a band costs a query rather than a replay. Every row is
self-sufficient: `rating_after` is already the running Value at that match, so
the line needs no window function and no ordering to be correct.

*(Amended 2026-08-03. Still a query rather than a replay, and still no window
function — but a row is no longer self-sufficient.
[ADR 0016](0016-peak-impact-and-the-ageing-curve.md) made `rating_after` the
stored peak `P`, so the drawn line is `P - D(age that day)` and the query needs
the player's date of birth and the ageing curve as well as his row. The curve
reaches it from `AgeingCurve`, and who is charged which curve from the run's own
`player_careers` table; neither is rewritten in SQL, for the reason
`ImpactIndex` exists. While stage 1's curve is pinned flat the drawn line is
`rating_after` exactly, and the change moved no point of the 607,016 on the
page.)*

## What this needs, and what it deliberately does not build

It needs the **results file** ADR 0009 designed and left unbuilt: the third
leg beside the read-only vendor snapshot and the precious sidecar, holding one
row per player per match for the designated run. Today those per-match
residuals exist for microseconds inside `MatchProcessor` and are discarded, so
no player's story survives the run. `MatchProcessor` gains a listener in the
established style of `ScoringWindow` — it hears, it changes nothing.

It needs **no date-of-birth loading in Java at all**, which was the original
shape of item 2 and turned out to be unnecessary. `rating_history` carries
`player_id`, the snapshot carries `date_of_birth`, and ADR 0009's design
attaches both files in one DuckDB connection. The date of birth was never
missing; it was unreachable, and the results file is what reaches it.

*(True of the chart, and no longer true of the model:
[ADR 0016](0016-peak-impact-and-the-ageing-curve.md) moved the age term inside
the replay, so an age is needed while a match is being played and not only when
one is being drawn. `TransfermarktLoader.birthDates()` loads the column.)*

It does **not** build Goalimpact's second line — **Peak Goalimpact**, the
dashed projection derived from a population ageing curve. That is a separate
object needing selection-corrected within-player comparison, birthday-to-
birthday binning, and a decision about left-censored careers. All of it runs
off the very history this ADR lands, so it is deferred rather than designed
away (item 21).

**Discharged by [ADR 0016](0016-peak-impact-and-the-ageing-curve.md)** — and it
turned the paragraph above inside out. The second line is not derived from the
first: the *peak* is the number the engine stores, and the index on this page is
that peak less an ageing penalty for the player's age that day. The
birthday-to-birthday binning is gone with it, since the curve is piecewise
linear in exact age. Two things here survive unchanged: this rescale is still
the only form in which a rating may be quoted absolutely, and the quality bands
stay **flat** — bending them by the curve would cancel the age term exactly
(#40).

## The match log, and what the single-file property bought (#24, 2026-08-03)

The chart is a *monthly sample* of a career, and the question it always
provokes is which matches made a month move. The **match log** answers it: one
row per match the run rated a player in, reached by opening a player — a
drill-down from the chart, not a second surface.

**It cannot live in the page, and both sides of that are measured.** The
eligible population's log is **2,185,765 rows** against the chart's **607,016**
monthly points, and at **102 bytes a row** it is **222 MB** where the page is
**12 MB** — and [ADR 0013](0013-spine-width.md)'s widening multiplies it ~4×.
[#35](https://github.com/JonasDockx/FootballImpact/issues/35) embedded the whole
population in one file at 7.1 MB on a measured number and said the decision was
re-openable only on another one. This is that number. The asymmetry is the whole
design: the population's log is impossible and **one player's is 50 rows**
(median; p90 201, max 686).

So the viewer becomes **a page plus a folder**: `ledger/000.js … 255.js`,
sharded `player_id % 256`, loaded a shard at a time by `<script>` tag rather
than `fetch()`, which is blocked on `file://`. Still no server.

**The single-file property was traded for the log and for nothing else.** The
page still carries every drawn career whole; what moved out is the one thing
that could not fit and was never in it. `ledger/` is **disposable exactly like
the page** — same lifecycle, same build step, rebuilt from the same two files in
the same reading ([ADR 0009](0009-transfermarkt-as-the-rating-spine.md)'s table
now lists it) — and it must travel with the page it was built beside.

**The log starts before the chart does.** It runs from the player's first rated
match, and the pre-threshold stretch — **394,821 rows, 18.1%** — is listed and
flagged *not drawn on the chart*. Those matches are real rating periods and they
moved the rating hardest, because the update factor is largest when exposure is
lowest; the line's first drawn value is made entirely of them. Their numbers are
**printed and greyed, never withheld**: this ADR's position is that a
sub-1,000-minute rating has no signal to *draw*, and item 48's is that
truncation misleads a reader worse than fading does. A log that cannot account
for its own first point is not one.

**A row reads the same number as the point above it**, and that is enforced
rather than intended. The level a row shows is `index(P − D(age that day))`
rounded to one decimal, computed by the *same* `ImpactIndex.sql` and
`AgeingCurve.penaltySql` expression that draws the chart point, and shipped in
the shard. The design note asked for `P` alone, with the level derived on the
page — which would have been a **JavaScript copy of the ageing curve**, the
second knot table `ImpactIndex.sql` exists to prevent, in the one language
`mvn test` cannot reach. It would also have been undetectable today, because the
curve is pinned flat. Six bytes a row is the price of not having it. The
residual stays in **goals**, its own natural unit, and `P` ships too, because the
arithmetic a row unfolds is in Value units.

**Guards are deliberately weaker than the chart's.** A missing `player_careers`
table makes this page refuse to draw at all, because a missing Goalkeeper flag
silently changed a drawn line. A missing, short, or wrong-run `ledger/` changes
nothing drawn — so the chart is drawn and the log panel states the reason in
words. What is never done is showing another run's rows: every shard carries the
run id, empty ones included, and a mismatch is refused outright.

**Gate, checked over all rows rather than eyeballed:**
`abs(goal_value + expectation_drained − residual) < 1e-9` on every row (worst in
the file **3.55e-15**); `after(i) == before(i+1)` for every player (2,159,653
links, **0 broken**, exact); every drawn chart point equal to its row's level to
1 dp (**607,016 points, 0 mismatched**); row count equal to `rating_history`'s
for the eligible population (**2,185,765**).

## Considered options

- **Value divided by exposure, per 90 (rejected — designed, built, and killed
  by its own gate).** This was the original decision of this ADR, on the
  reasoning that Value was a total needing conversion to a level, and on
  Goalimpact's own description of its line as a "career average". The written
  history refuted it on first contact. Career minutes keep growing while Value
  plateaus, so the ratio decays as ~1/exposure: across the fixed 234-player
  cohort the implied rate falls 0.31 → 0.037 in near-exact proportion to
  1/minutes. The consequence at the top was absurd — **Messi would score 102,
  van Dijk 96, Müller 105**, while a reserve goalkeeper with 1,044 minutes
  scored 212, because the ratio's variance explodes near the eligibility
  threshold. Every version was tried before abandoning it: a 10,000-minute
  threshold, and shrinkage toward the mean by 4,000 and by 20,000 minutes.
  Raising the threshold produced a defensible list; shrinkage did not, at any
  constant. Both were treating a symptom. Kept here in full because the wrong
  turn was caught by exactly the eyeball gate this ADR wrote for it, and that
  is the strongest argument the gate has.
- **A rank or percentile scale (rejected).** Would fill the 100–200 range
  neatly and be immune to outliers, but ranks destroy distances: the step from
  the 50th to the 60th percentile is negligible in football terms and large in
  rank terms, so the shape of every line would be an artefact of how crowded
  the middle is. Goalimpact's numbers behave like a measuring scale, not a
  ranking.
- **Re-centre the scale each season (rejected).** The ruler would change length
  along the axis being measured.
- **Calibrate the scale on all 94,807 rated players rather than the eligible
  25,334 (rejected).** Most of that population is cup opposition with a few
  hundred minutes, sitting near 0 because the model has no evidence about them
  rather than because they are average. Anchoring 100 on them would define the
  average player as somebody the model has never seen.
- **Plot residuals per 90 taken before the update factor (rejected).** Proposed
  during the grill as the "purest" rate, since `K` shrinks with exposure by
  design. Rejected because residual is *surprise relative to expectation*, so a
  correctly rated player scores near zero however good he is. The update factor
  is part of the estimate, not noise in it.
- **A rolling recent-form window (rejected as the main line, kept as a possible
  second).** Reacts fast, so a genuine decline appears as a fall rather than a
  sag. But it is noisy for squad players, who take two seasons to reach 2,000
  minutes, and it is not what Goalimpact draws.
- **Aggregate age-years inside the replay (rejected).** ~150,000 rows instead
  of 2.35M and much less machinery, but it bakes a binning decision into the
  engine and destroys the per-match detail permanently — including the detail
  that overturned this ADR's first draft.
- **Per-match rows to a plain CSV (rejected).** No new dependency, but leaves
  ADR 0009's three-file design half-built and turns every later question into a
  script rather than a query.
- **Drop each player's first ~2,000 minutes before reading him (deferred to an
  off-by-default knob).** The model rates an unseen player 0, which here means
  *exactly average* rather than *unknown*, so a genuinely good newcomer banks
  an unpriced bonus until expectation catches up. Much less damaging now that
  the index is a level rather than a ratio, but still visible in the top 20
  (Franculino, 4,777 minutes).

## Consequences

- **The eyeball gate did its job, and it is not decoration.** The first design
  passed every unit test, wrote 2,352,025 correct rows, reconciled to 94,807
  distinct players exactly, and was wrong. What caught it was reading twenty
  names. Any future change to how a rating is displayed gets the same check.
- **Every on-pitch player receives the whole team's residual, not a 1/11
  share.** So Value is "how much this player's side out-performed expectation
  while he was on" — a plus/minus quantity that says something about *him* only
  by averaging over many teammate combinations across a career. It is not
  Goalimpact's "with him versus without him" difference. Numerically it does
  not matter, because the rescale absorbs the units; it matters for how much
  precision the chart is allowed to imply.
- **`Value`'s glossary caveat needs reading carefully, not deleting.** It says
  only *gaps* are meaningful and never absolute levels, because per-player
  update factors mean population totals are not conserved. That remains true of
  a raw Value compared across runs. The index does not contradict it — it makes
  a level meaningful only *relative to one stated population, measured once*,
  which is exactly what an index is and exactly why the constants are pinned
  and dated.
- **Glossary:** *Impact index* added, defined against the existing *Value*.
- **Backlog items 2 and 8 close together.** Item 2's date-of-birth work reduces
  to a join that the results file makes possible; item 8's
  "population-anchored scale with known reference points" is the index. Item
  2's original goal — the asymmetric ageing curve and Peak-GI forecasting —
  becomes item 21 on top of the history.
- **The results file stops being a design and starts being a file.** ADR 0009
  specified it and shipped without it; the career chart is the first thing that
  cannot be built without it.
- **The scale constants belong to this spine.** Like the base scoring rate and
  `h`, they were measured on 80,471 Transfermarkt matches and are not
  transferable to StatsBomb on faith.
