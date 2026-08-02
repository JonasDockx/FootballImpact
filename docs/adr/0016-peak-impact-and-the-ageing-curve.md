# The stored rating is a player's peak, and age is subtracted inside the replay

The engine still keeps **one number per player** and still moves it the way
ADR 0005 and ADR 0006 say. What changes is what the number **means**:

```
stored:    P                       the player's estimated PEAK level
strength:  P - D(age at kickoff)   what he contributes to a lineup today
update:    P <- P + K x residual   unchanged; ADR 0006's K schedule as-is
```

`D(age)` is the **ageing curve**: how far below his own peak a player of that
age is, in rating points, with `D(peak age) = 0`. It is subtracted **inside the
replay**, before a single goal is judged — not drawn on the chart afterwards.

The two lines of the career chart then come from **one** stored number read two
ways: **Peak Impact** is `P`, and the **Impact index** is `P - D(age that day)`.

## Why the arrow points this way

Item 21 was written the other way round: Peak Impact as a *projected ceiling*,
obtained by reading a player's current index against the population curve. The
#38 research established from Seidel's own 2015 post (*How to read a Goalimpact
Chart?*) that the original does the opposite — "the expectations on how good a
Zlatan will be in future are derived from the Peak Goalimpact and the aging
curve of field players". The peak is the **latent parameter**; the visible line
is that parameter bent down by age.

The direction is not cosmetic, and it is the whole reason this ADR exists.

**Ageing becomes predicted rather than learned late.** If the stored number is
"his level today", then a 34-year-old's decline can only enter the model
*after* the residuals have punished him for it — the expectation at kickoff is
still yesterday's level, so his side is over-rated in every match while the
decline is being discovered. With the curve inside the replay, the model expects
a 34-year-old to be worse *before* the ball is kicked, and only a departure from
the curve moves his number.

**The two chart lines become independent.** Under the rejected direction the
dashed line is a deterministic function of the thick one, so it carries no
information the thick line does not — one line drawn twice. Here `P` and
`P - D` genuinely differ: the gap between them *is* the age term, and a player
whose thick line falls while his dashed line holds is declining exactly on
schedule.

**Item 42 is absorbed.** A debutant enters at the population average *as a
peak*, and the engine then subtracts the penalty for the age he actually is. A
seventeen-year-old and a twenty-seven-year-old no longer enter the run as the
same player, which is precisely what item 42 asked for — with no debutant-aware
special case anywhere.

## Shape of the curve

**Piecewise linear in EXACT age, between fixed knots.** A player is 22.37 years
old on the day, not "in his 22nd year". This dissolves item 21's original
problem, which was real: the spine's competitions do not share a calendar, so
"a season" has no single meaning across it while a birthday always does — and
the year-bin design that followed had to discard short careers and final
seasons for want of ~900 minutes in both of two adjacent years. There are no
bins now, so nothing is discarded and no birthday is a boundary.

**Fitted once outside the engine, then pinned and dated**, like the base scoring
rate, `h`, `K0` and `H`. The engine reads a lookup and stays a single sequential
chronological pass. One bootstrap step, not a fixpoint: fit on a run, replay
with the curve, refit as a check that it barely moved.

**Fitted only on the wide spine (ADR 0013).** Thirteen years against a
twenty-year career means almost nobody in today's window has both ends of his
career in the data, so a curve fitted now would be survivorship at both ends.
The fit joins the single re-measure ADR 0013 schedules for the end of item 30's
third pass — which is what makes this a two-stage change rather than one.

**Field players only.** Goalkeepers age differently and are #44. Stage 1 does
**not** honour this — `MatchProcessor` ages every man it freezes, keepers
included — because the flat curve makes it inert and because what a keeper
should be charged instead is exactly #44's open question, which stage 1 must not
answer by default. **Stage 2 does not ship until #44 answers it**, and the carve-
out lands at `freeze`, which already knows who is a keeper (item 11's career
tag).

**A missing date of birth gets the population-average penalty.** The age term
then says nothing about him, which is exactly true. This is a real constant with
real exposure now: ADR 0011 could note that only 15 of 17,030 charted players
lacked a date of birth, but the curve is read for **every man on the pitch**,
not just the charted ones. Measured on the designated run of 85,050 matches
(2026-08-02): **40,350 of 95,521** men who appear have a date of birth (42.2%),
covering **2,250,909 of 2,478,286 appearances (90.8%)** — the missing ones play
little, which is the same fact ADR 0009 predicted at 90.3% and ADR 0011 saw at
the chart's threshold.

## The curve is not drawn as a chart background

The original draws age-dependent **envelopes** behind the line. This project
does not, and #40 settled why: bend a band by this curve and the age term
cancels, since `P - D(age) >= 140 - D(age)` is just `P >= 140`. Reading the
thick line against a bent band is *exactly* reading the dashed line against a
flat 140. The envelope is already on the chart — drawn as a second series
rather than as a background. ADR 0011's bands stay flat at 100/140/150/170.

The original needs envelopes because it publishes one line. We publish two.

## Staging and gate

**Stage 1 — the whole mechanism, every penalty zero.** Landed 2026-08-02. The
curve is built, every man on the pitch is aged, dates of birth are loaded, and
lineup strength is `P - D`; `D` is pinned at zero at every knot and for an
unknown date of birth. Gate: **byte-identical CSV and identical log-loss**.

Met. Same CSV (md5 `6319a65d…`), and every cell of the grid unchanged over
85,050 matches, 2012-07-09 to 2026-07-06:

| seed | windowed | whole | bridge |
|---|---|---|---|
| 0.00 | 0.6503 | 0.6510 | 0.6457 |
| 2.58 | 0.6481 | 0.6488 | 0.6341 |

**Stage 2 — the fitted curve**, after item 30's third pass. Gate:
**whole-population log-loss, strictly better at four decimals, against a
same-run curve-off baseline** — not against the pinned champion, which was
measured on a population ADR 0013 deletes (ADR 0014, rules 3 and 4). Reported
alongside but not gated on: log-loss over matches involving under-21s and
over-33s, and ADR 0011's eyeball check.

## Decisions

**The age term is read once per player per match, not per rating read.** A
player's age cannot change inside a match, and the rating seam is read on every
goal and every lineup-constant segment. `MatchProcessor` freezes two maps where
it used to freeze one: `frozen` holds `P` — what the update moves and what the
history records — and `strength` holds `P - D`, which is all the credit rule
ever sees.

**The engine carries no calendar.** `AgeingCurve.at(kickoff)` returns an
`AgePenalty` already bound to the match date, and that is what `process` takes.
The engine asks a lookup for a number, exactly as it does for a rating —
`AgePenalty` is deliberately the same shape as `RatingLookup`.

**The mechanism-off arm is the all-zero knot table, not a second code path.**
`AgePenalty.NONE` exists and is what the older `process` overloads pass, but the
designated run always builds the real curve — so stage 1 exercises the shipped
mechanism rather than a bypass of it, which is what makes the byte-identity
result worth anything. Turning the curve off after stage 2 means pinning the
penalties back to zero, one edit in one table, in the spirit of ADR 0014 and of
`FIELD_PLAYERS_ONLY = {false}`.

**Dates of birth are loaded in Java after all.** ADR 0011 said this work needed
"no date-of-birth loading in Java at all", and that was true of the *chart*: the
index is computed at reporting time, where SQL reaches `players.date_of_birth`
itself. Moving the age term inside the replay is what changed it — an age is now
needed while the match is being played. `TransfermarktLoader.birthDates()` reads
the column whole, once; ADR 0012's register wins over the vendor for a
hand-typed player, here as everywhere.

**The curve holds its end values outside the fitted range.** It is a statement
about the ages it was measured over. Extending the last slope invents a
fifteen-year-old prodigy at one end and a negative penalty at 44 at the other.

**A malformed knot table fails at construction.** These are pinned constants, so
a bad table is a typo in them, not an input.

## Considered options

- **Peak as a projected ceiling read off the current index (rejected).** Item
  21's original direction. Refuted as a description of the original by #38, and
  rejected on its own merits: the dashed line becomes a function of the thick
  one, so it is one line drawn twice, and ageing still enters only after the
  residuals have paid for it.
- **The curve applied at reporting time only (rejected).** Cheap, reversible in
  a query, and exactly the ADR 0011 posture that made the index a reporting
  concern. It cannot work here, because the point is the *expectation at
  kickoff*: a chart-only curve leaves every prediction involving an ageing
  player wrong, and the residual then re-learns the decline the model already
  knew about.
- **Year bins, birthday to birthday, with a ~900-minute gate in both years
  (rejected).** Item 21's own design. Exact age makes the bin unnecessary, and
  the bin's minutes gate discards precisely the short careers and final seasons
  the old end of the curve is made of.
- **Fit the curve on today's 2012–2025 window (rejected).** Deliverable now, and
  survivorship at both ends: the players still on a pitch at 34 in a thirteen-
  year window are the ones good enough to have survived it.
- **The delta method — chain year-on-year within-player steps (deferred, not
  rejected).** Item 21's stated method, and the field standard (Lichtman 2009).
  It does not remove survivorship, and the literature disagrees on the sign of
  what is left — Lichtman says the residual inflates decline, Judge (2020) says
  it understates ageing. Which estimator fits the knots is a stage 2 decision,
  taken with the wide spine in hand; the plain per-age averages are printed
  beside whatever is chosen, because the gap between the two lines *is* the
  selection effect.
- **A zero penalty for an unknown date of birth (rejected as the answer, and
  what stage 1 ships anyway).** Charging nothing prices an unknown player as one
  at his peak, which is a claim rather than an absence, so the constant is the
  population average. At stage 1 that average *is* zero, because every penalty
  is — the pin is the flat curve, not a decision about unknown ages, and stage 2
  measures it as the minutes-weighted mean over the population that has a date.

## Consequences

- **Every rating changes at stage 2**, so ADR 0011's scale constants (mean
  1.8374, sd 7.1729) and #47's eleven rank ticks are measured on a quantity that
  no longer exists. Both are already inside ADR 0013's single re-measure.
- **The viewer still draws one line.** #22 shipped with `PEAK_LINE = false` in
  `src/main/resources/viewer/goalimpact-viewer.html` because `P` did not exist;
  at stage 1 it exists but equals the index exactly, so nothing is gained by
  turning it on. The flag is not the whole of stage 2, though, and the gap is
  worth naming now: `rating_history` stores `P`, so the viewer's **thick** line
  has to become `P - D(age that day)` — which means the knot table must reach
  `ViewerWriter` as well as the engine. It reaches it from `AgeingCurve`, the way
  `ImpactIndex` and `RankLadder` already hold their constants for the query, the
  page and the test to share. A second copy of the curve written in SQL is
  exactly the drift `ImpactIndex` exists to prevent.
- **#40 is discharged and ADR 0011's bands are untouched.**
- **ADR 0011's deferral of "Goalimpact's second line" is discharged in design.**
  The object is specified and built; the numbers wait on the wide spine.
- **Glossary updated:** *Peak Impact* and *Ageing curve* added, *Impact index*,
  *Value* and *Strength* redefined.
