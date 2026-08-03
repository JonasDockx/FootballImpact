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

**Two curves, and the field one is fitted on field players only.** #44 answered
this on 2026-08-03. Stage 1 does **not** honour the split — `MatchProcessor` ages
every man it freezes, keepers included — because the flat curve makes it inert.
Stage 2 fits a **second curve on keepers only**, and the field curve is fitted on
field players only *in every arm*, including the one that then charges keepers
that same curve. Keeper exposure is concentrated exactly where the field curve
is thinnest — keepers are 7.4% of starts at 25, 34.9% at 38 and 54.8% at 41 —
so one pile gives a field curve whose old end is largely a keeper curve wearing
an outfielder's name, and a baseline that is already a blend.

**The keeper curve has its own knots: 19, 23, 26, 29, 32, 35, 38, 42.** It starts
at 19 because only 1.0% of keeper starts happen before 20 — nineteen starts at
age 16, by five players — and a knot there would be noise that then leaks down
the straight line into ages 17–19, where 2,277 starts do exist. A 16-year-old
keeper is not left out: the curve holds its first knot's value below it, so he is
charged the age-19 penalty, the youngest one measured. It ends at 42 because 282
keeper starts happen there and the field curve has run out. Measured on the
stage 3 snapshot (135,166 matches, 2006-06-09 to 2026-07-06): **219,886 keeper
starts carry a date of birth**, across 3,754 keepers, with 843 distinct keepers
starting at 33 and 212 at 38. Exposure was never the constraint.

**A keeper with no date of birth is charged the keeper average**, its own pinned
constant — the minutes-weighted mean penalty over keepers who do have one. Same
reasoning as the field constant below, applied to the population the curve
describes. On the stage 3 snapshot keeper coverage is **82.7%** against field
players' **80.9%**: keepers are slightly better covered, not worse.

**The carve-out lands at `freeze`, and the career tag is stamped there.** ADR
0016 as first written said `freeze` "already knows who is a keeper (item 11's
career tag)". It did not: the freeze pass walks the events before the main loop,
and `startedInGoal` is stamped inside it, so a keeper making his **first career
start in goal** was frozen — and aged — as a field player. Order 8,679 matches,
one per keeper. The stamping moves into the freeze pass, which already walks the
same Starting XI events. It is byte-identical today, because the tag is read only
through `Lineup.goalkeepers()` and only matters when `fieldPlayersOnly` is true,
which is pinned false — so it lands with stage 1 rather than waiting for stage 2.
The glossary's **career** tag stays the single definition of Goalkeeper; reading
`s.goalkeeper()` off the event instead would need no code to move but would put a
second meaning beside it and charge a substitute keeper the field curve.

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

**Stage 2 runs three arms, and the keeper arms carry their own aimed gate.**
The arms are **A**, every man charged the field curve — this ADR as first
written, and the arm to beat; **B**, keepers charged their own fitted curve; and
**C**, keepers charged nothing while field players are charged theirs. C is in
the comparison because the record has never said whether the original leaves
keepers out of the *curve* or out of *ageing* — Seidel 2013 and Wittmütz 2017
only say keepers are treated differently — and a fitted keeper curve that comes
out flat is not the same finding as no penalty at all. If both B and C clear the
gate the better takes it; if neither does, **A stands** and the losers are pinned
off in the code the way `FIELD_PLAYERS_ONLY = {false}` is. Both curves are fitted
with the **same estimator**, whichever the deferred choice below lands on, so the
comparison between arms is not confounded by it (ADR 0014, rule 4).

B and C are judged in **ADR 0014's shape, not on the whole population**:
primary, log-loss over matches where the two **starting keepers are eight or
more years apart in age**, strictly better at four decimals; guard,
whole-population log-loss not worse at four decimals; both read from a same-run
baseline. A keeper is one man in eleven, so ~9% of a side's strength, and the
prediction reads only the *gap* — item 16 touched 13.4% of matches with a far
larger per-match effect and still moved whole-population log-loss by 0.0022, so
the plain gate would discard a correct keeper curve as a tie. The slice is the
same order as item 16's bridges: both keepers' birthdays are known in 97,992
matches (72.5%), the mean gap is 5.08 years, and 21.3% of those matches are eight
or more years apart — **15.4% of all matches**. This is not the circular option
ADR 0014 rejected: the curve is fitted on keeper careers at every age, and the
eight-year slice is a property of the fixture list, exactly as a bridge is a
property of the run's coverage.

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
- **One curve for everyone, keepers included in the fit (rejected).** The
  simplest thing, and the reason it fails is the exposure table: at 41 more than
  half the men starting are keepers, so the old end of a single curve describes
  keepers while claiming to describe everyone. It also destroys the baseline —
  arm A only means something if the curve it charges keepers was fitted without
  them.
- **A separate keeper curve, committed to in advance (rejected).** Every
  first-party and academic source says the keeper trajectory differs, and the
  exposure table says so too. But none of them agree on how — 27, 31 and 33 are
  all published peaks — and CLAUDE.md's rule is that model quality is a measured
  number. What #44 settles is the experiment, not the result.
- **The keeper curve sharing the field knots (rejected).** Directly comparable
  knot for knot, and easy to chart side by side. Rejected on both ends: the knot
  at 16 would rest on five players, and the curve would go flat from 40 onward,
  discarding the one age range where keepers are the interesting case.
- **Judging the keeper arms on whole-population log-loss (rejected).** One rule
  for the whole curve, no new column. It would reject a correct keeper curve
  before it was ever measured, which is the failure ADR 0014 exists to prevent.
- **Aiming the gate at matches with a keeper under 24 or over 33 (rejected).**
  Where the curve is steepest, so a natural choice, but one-sided: two
  38-year-old keepers land in the slice while both arms price the match almost
  identically, which dilutes exactly the signal being measured.
- **The viewer re-deriving the keeper tag in SQL (rejected).** Nothing to change
  in the results file. But it is a second copy of a definition, and it can
  genuinely disagree — the SQL sees matches the run dropped as unusable, so a man
  who only ever kept goal in a discarded match would be a keeper to the chart and
  a field player to the model that rated him. The run records the flag instead,
  the same argument that keeps the knot table out of SQL.
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
- **#40 is discharged and ADR 0011's bands are untouched** — by the keeper split
  as well. #40's cancellation holds for whichever curve a player is charged, and
  keepers sit on the same part of the scale: past 1,000 minutes on the stage 3
  results file, field players are n=33,519, mean 0.780, sd 7.218 and keepers are
  n=3,064, mean 0.583, sd 7.249 — **0.55 index points apart** at 20 points per
  sd, with an identical spread. #47's rank ladder means the same thing for a
  keeper too.
- **The results file records who is a keeper**, and `ViewerWriter` reads the
  flag. With two curves the page needs to know which one a player is drawn
  against, and the replay is the thing that knows it authoritatively. Where the
  flag lands in the file is #22's call; it holds `rating_history` and a small
  `appeared_players` table and records position nowhere today.
- **ADR 0004 is not threatened by any of this.** The keeper tag lives on the
  tally, never on `Player` — `Lineup` already carries `Set<Player> goalkeepers`
  — so record equality and on-pitch set removal are untouched, and none of the
  date-of-birth trap #42 flags applies.
- **ADR 0011's deferral of "Goalimpact's second line" is discharged in design.**
  The object is specified and built; the numbers wait on the wide spine.
- **Glossary updated:** *Peak Impact* and *Ageing curve* added, *Impact index*,
  *Value* and *Strength* redefined.
