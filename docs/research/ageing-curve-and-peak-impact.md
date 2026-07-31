# The ageing curve and Peak Impact: what the record states, and what the literature does

Research note for [#38](https://github.com/JonasDockx/FootballImpact/issues/38) (child of
[#31](https://github.com/JonasDockx/FootballImpact/issues/31)), feeding
[#21](https://github.com/JonasDockx/FootballImpact/issues/21). Compiled 2026-07-31.

Terms are [CONTEXT.md](../../CONTEXT.md)'s: *Impact index*, *Value*, *Exposure*,
*Left-censored career*. Where a source uses "Goalimpact" or "PeakGI" for its own
quantity, that is the source's word and is kept in quotation.

**Reading guide.** Section 1 separates what Goalimpact *states* from what others
*infer*. Section 2 is the outside literature. Section 3 answers #21's
data-sufficiency claim. Section 4 lists what the record does not say.

---

## 1. What the public record states about Peak Goalimpact

### 1.1 The chart's two lines — stated, by Jörg Seidel, 2015

The single most direct statement is Jörg Seidel's own blog post *How to read a
Goalimpact Chart?*, goalimpact.com, 12 April 2015. The page 404s on the live
site; the text below is from the Internet Archive capture of 6 March 2020
(`web.archive.org/web/20200306004714id_/http://www.goalimpact.com/blog//2015/04/how-to-read-goalimpact-chart.html`).
Verbatim:

> The thick line shows the Goalimpact at that time. This is the original estimate
> not using any future games. Clearly, with hindsight, we may give him another
> rating, because his team outperformed or underperformed original expectations.
> The expectations on how good a Zlatan will be in future, are derived from the
> Peak Goalimpact (thin dashed line) and the aging curve of field players.

> If the team results of all of Zlatan's games are better than expected by his
> Goalimpact, the PeakGI line will raise. It will do that whenever the player
> overachieves original expectations, independent if he passed his peak already or
> not. In the recent year, for example, his PeakGI raised because his performance
> dropped less than we would have expected given the typical aging effect in
> football. Since 2011, the peak rose nearly 15 points. Without that raise, his
> Goalimpact would have been 15 points lower than it is today, so 140 instead of
> 154 points.

On Theo Walcott, same post:

> Walcott delivered as expected until 2012. Therefore his PeakGI was more or less
> stable and the Goalimpact rose along expectations given the aging curve. From
> 2012 on, the team strongly outperformed expectations when he played and hence
> his PeakGI rose consistently […]

**What this settles.** Peak Goalimpact is not a *forecast drawn from* the current
rating. It is the other way round: **PeakGI is the estimated latent parameter and
the thick line is PeakGI shifted along the population ageing curve to the
player's current age.** The two lines are one estimate seen at two ages. PeakGI
moves only when the observed result differs from what PeakGI-plus-ageing-curve
predicted; if the player ages exactly as the curve says, PeakGI is flat and the
thick line still bends. That is the opposite of the reading in #21 ("a player's
projected ceiling, obtained by reading his current index against the population's
ageing curve") — the arrow points from Peak to current, not from current to Peak.

This is also confirmed independently: Dr. Patrick May, *Goalimpact 1x1*, Moneyball
Letters (Substack), 4 September 2023 — "the red-dotted line is the career
Goalimpact predicted rating based on average player development."

### 1.2 How the curve is built — stated, by Jörg Seidel, 2013

Jörg Seidel, *The Football Aging Curve*, goalimpact.com, 15 December 2013 (same
archive route, capture 20200306004714, path
`/blog//2013/12/the-football-aging-curve.html`). This post is the announcement of
the age factor entering the algorithm. Verbatim:

> To circumvent the issue, we introduce as of today a new factor to Goalimpact.
> All the results of all matches will be set in to the perspective given the age
> of the players involved. This allows for cross-time averaging without
> introducing a bias for young and old players. The Goalimpact of young players
> will be lower just because they are young and so will be the score of old
> players. **This aging curve itself is based on the average age profile of all
> non-goalkeepers in the database.** This is such a large amount of data that
> allows us to come up with a precise average football aging curve. The individual
> ups and downs of players will be still averaged out. However, the systematic
> effect of age is compensated for.

And the shape, verbatim from the figure caption:

> The football aging curve increases until an age of approximately 26 and drops
> thereafter. But the differences between the age of 25 until 30 are minor. After
> 30 the slope becomes considerably negative.

Two further things the same post states plainly, both of which the project has
already re-derived on its own data:

- The pre-2013 Goalimpact was a **career average** with no age term, and Seidel
  names its failure mode: "Young players will be scored too low […] But very old
  players see the opposite effect. They will be overvalued because the career
  average drops only a tiny little bit each game." His example: Edgar Davids,
  career average 147, current -31. This is the same distinction ADR 0011 draws
  between averaging *across* people and *within* a person.
- The peak-at-26 number is imported, not derived: "the market values as published
  by transfermarkt.de show a peak around the age of 26."

**What this settles.** The curve is, in Goalimpact's own words, the **average age
profile of the whole non-goalkeeper population** — a cross-sectional average by
age. There is **no statement anywhere in the public record** that it is corrected
for survivorship, chained within player, or fitted with player effects. See §4.

### 1.3 The asymmetry claim — stated, but by Wittmütz, not Seidel

#21, #2 and #38 attribute the asymmetric-curve claim to an Übersteiger interview
with Jörg Seidel. **The interview is with Thorsten Wittmütz of Goalimpact, not
Jörg Seidel.** (*Timbos kleine Taktikschule — Heute: Goalimpact*, Der Übersteiger,
print issue 130, 10 December 2017; online 19 March 2018,
`blog.uebersteiger.de/2018/03/19/timbos-kleine-taktikschule-heute-goalimpact/`.)
Seidel is named in it as founder and inventor but does not speak. The
substance of the claim survives the correction; the attribution does not.

Verbatim (Wittmütz):

> Da wurde dann irgendwann festgestellt, dass das Alter eines Spielers
> entscheidend ist. Der Karriere-Höhepunkt liegt rein statistisch gesehen bei 26
> Jahren. Da gibt es natürlich Ausnahmen wie Ibrahimovic oder Ronaldo, aber über
> alle Spieler in unserer Datenbank ist das der Höhepunkt. Im Regelfall ist danach
> der Einfluss eines einzelnen Spielers auf einen Spielausgang nicht mehr so hoch.
> **Allerdings nimmt dieser Karriere-Höhepunkt langsamer ab als er steigt.**

("The career peak lies, purely statistically, at 26 […] However, this career peak
declines more slowly than it rises.")

The same interview shows Peak in operational use: the FC St. Pauli U23 has "einen
Goalimpact von 91 […] Der Peak der aktuellen Mannschaft liegt bei 118" — current
91, Peak 118, the gap explained by the squad's low average age. It also gives the
two landmarks ADR 0011 already calibrates the Impact index against: world class
above 160, roughly 200 such players worldwide, Bundesliga average 135, best in the
world Pogba at 200.

**Caution.** Wittmütz's asymmetry statement is *in tension with Seidel's own 2013
figure caption*, which says the curve is near-flat from 25 to 30 and
"considerably negative" after 30. "Rises fast, plateaus, then falls off a cliff"
and "declines more slowly than it rises" are not the same shape. Both are
first-party Goalimpact statements, four years apart, and the record does not
reconcile them. Treat the asymmetry as **stated but not corroborated by the one
published picture of the curve**.

### 1.4 The 2022 restatement — a different peak age, and the numbers behind the asymmetry

Dr. Patrick May, *How player age (goal)impacts performance*, Moneyball Letters,
22 December 2022 (`goalimpact.substack.com/p/is-player-age-the-most-important`).
Verbatim:

> A huge advance in the Goalimpact algorithm was to investigate the average Impact
> of all players in dependence on their age. The result is the Goalimpact Aging
> Curve […]

> The graph shows that an average player reaches his Goalimpact Peak (highest
> impact on the goal difference) around 27.

> a player's impact remains relatively stable over the next years. There is only a
> ~10% decrease between the age of 27 and 35.

> the biggest rise in Goalimpact occurs between the age of 14 to 16 (Note: Data
> for younger players are unavailable and/or reliable […]). Within these two
> years, however, the Goalimpact increases by more than 100.

> Compared to field players, Goalkeepers have their highest Goalimpact at age 33,
> a + 6-year difference. […] Their level of performance remains relatively stable
> until the age of 42.

**This is where the asymmetry actually has numbers behind it**: >100 index points
gained over two years at 14–16, against ~10% lost over eight years from 27 to 35.
On those figures the rise is overwhelmingly steeper than the decline, and the
Wittmütz statement is supported — but only if the curve is extended down to age
14, which is far outside anything this project's spine will ever see.

Note also the **peak age discrepancy in first-party sources**: 26 (Seidel 2013),
26 (Wittmütz 2017), 26 (May, *Goalimpact 1x1*, 2023), **27** (May, 2022, for field
players, with goalkeepers at 33). The record does not explain the change. It may
be a genuine re-fit, a different population, or loose rounding; nothing states
which.

### 1.5 Inputs — stated

Two first-party statements about what the algorithm consumes:

- May, *Goalimpact 1x1* (2023): "The algorithm just needs match data as inputs,
  such as **the starting lineup, goal minutes, player subs, and birthdates of the
  players**. Starting with 1,000 minutes of playing time, the computer can
  determine player quality using these data. Red cards, fatigue levels, and
  home-field advantage are also factored into the formula. […] This allows also to
  calculate a forecast of the player's future potential using their date of birth,
  and the huge database. The highest expected Goalimpact is called Peak and is
  around the age of 26."
- Wittmütz (2017): "Als Daten-Input nutzen wir den klassischen
  Spielberichtsbogen. Datenpunkte sind für uns dabei die gefallenen Tore, Ein- und
  Auswechslungen und rote Karten." (Plus the lineup, named in the article's
  standfirst.) Wittmütz does **not** list date of birth among the inputs, though
  the whole age discussion presupposes it.

### 1.6 Stated versus inferred — the ledger

| Claim | Status |
|---|---|
| The thin dashed line is Peak Goalimpact | **Stated** (Seidel 2015) |
| Current rating = PeakGI read forward/back along the population ageing curve | **Stated** (Seidel 2015) |
| PeakGI moves only on over/under-performance against that expectation | **Stated** (Seidel 2015) |
| The ageing curve is the average age profile of all non-goalkeepers in the database | **Stated** (Seidel 2013) |
| Peak at ~26 | **Stated** (Seidel 2013, Wittmütz 2017, May 2023) — and openly imported from Transfermarkt market values in 2013 |
| Peak at ~27 for field players, 33 for goalkeepers | **Stated** (May 2022), contradicting the above without explanation |
| Curve rises faster than it declines | **Stated** (Wittmütz 2017); **numerically supported only by May 2022's 14–16 vs 27–35 figures**; **in tension with Seidel 2013's own caption** |
| Goalkeepers are treated separately | **Stated** (Seidel 2013 "non-goalkeepers"; Wittmütz 2017 "da diese etwas anders bewertet werden"; May 2022 separate GK curve) |
| Inputs are lineups, goal minutes, subs, red cards, dates of birth | **Stated** (May 2023; Wittmütz 2017 minus DOB) |
| The curve is corrected for survivorship / selection | **Not stated anywhere.** The only wording given ("average age profile of all non-goalkeepers") describes the uncorrected cross-sectional average |
| The functional form (spline, piecewise linear, parametric) | **Not stated** |
| How PeakGI is initialised for a debut player | **Not stated** |
| Whether the curve is re-fitted, and on what population | **Not stated** |

Everything in the "not stated" rows that appears in secondary write-ups is
inference. This note found no first-party source for any of it.

---

## 2. What the football-analytics literature does

### 2.1 The problem, stated precisely

Schuckers, Lopez & Macdonald, *What does not get observed can be used to make age
curves stronger: estimating player age curves using regression and imputation*
(arXiv:2110.14017; Springer, *Journal of Quantitative Analysis in Sports* line of
work). Abstract:

> Most research has focused on the performance of players at each age, ignoring
> the reality that age likewise influences which players receive opportunities to
> perform.

Their demonstration is the cleanest available. Fitting a cubic spline to observed
NHL forward data with **no player effects** produces a curve that *turns upward
after age 33*:

> The naive spline model does not decrease as it should for older ages because of
> selection bias: the only players that are observed at older ages are very good
> players.

This is exactly the failure ADR 0011 already caught once on this project's own
data — the exposure table that "binned *different players* by career length, so
it was measuring survivorship". The literature's verdict matches: the naive
per-age average (Schulz et al. 1994) "would only be valid if players were chosen
to participate in sport completely at random, making it too unrealistic for
professional sport."

**#21's plan to print the plain per-age averages alongside the corrected curve is
well-founded**: the gap between them is the selection effect made visible, and
Schuckers et al.'s Figure 3 is a published picture of how large it can get.

### 2.2 The delta method — which is precisely what #21 proposes

Mitchel Lichtman, *How do baseball players age?*, The Hardball Times / FanGraphs,
2009. Schuckers et al. call it "the de facto standard methodology in the sport
analytics literature". The method: for each pair of consecutive ages, average the
year-on-year change **over only the players observed in both years**, then chain
those steps into a curve.

Lichtman explicitly discusses the weighting choice — equal weights, "the lesser of
the two PA", or the average of the two — and prefers the average, noting "there is
very little difference between the two."

**#21's design *is* the delta method**, down to the weighting: "Chain year-on-year
steps instead, weighting each step by the smaller of the two years' minutes."
That is Lichtman's "lesser of the two" option. This is worth stating on the issue,
because it means #21 inherits a named method with a twenty-year literature — and
its known criticisms.

### 2.3 The delta method's own bias — and the field disagrees on its sign

The delta method **does not eliminate survivorship**; it only moves it. To
contribute a step from age *t* to *t+1*, a player must still be in the league at
*t+1*.

- **Lichtman (2009)** measures the residual bias and says it inflates decline:
  survivor bias produces "more decline (and less improvement) than it should at
  every age interval", because players who were unlucky-low in year I are
  disproportionately cut and never appear in year II. He quantifies the dropout:
  7 non-survivors per 100 survivors at ages 20–24, rising to 16 per 100 at 28–35
  and 30 per 100 after 35.
- **Jonathan Judge (2020)**, *An approach to survivor bias in baseball* and *The
  Delta Method, Revisited: Rethinking Aging Curves*, Baseball Prospectus, argues
  the opposite sign: his prior work found "survival bias either doesn't materially
  exist at all or if it does exist, that it causes the pool of survivors to
  *understate*, not overstate aging effects." He also faults the delta method for
  discarding "over 20 percent of the data" — every final career season and every
  one-season player — and argues the real distortion is at the **young end**, from
  *entry* selection: early arrivals are better than average, are overrepresented
  in young age bins, and so pull the apparent peak earlier.
- Schuckers et al. side with Judge on direction: "if dropout rate is linked to
  performance, that effectively shifts the age effect downward, relative to
  surviving players."

**Consequence for #21.** The chained within-player curve is the right first move
and is the field standard, but it is not a solution to survivorship — it is a
large reduction of it with a residual whose *sign the literature does not agree
on*. Any claim this project makes about the tail should be hedged accordingly.

Judge's entry-selection point is also the mirror image of this project's
*Left-censored career*. #21 already identifies the young-end artefact from the
model rating an unseen player 0; Judge identifies a second, independent young-end
artefact from *who arrives early*. ADR 0011's off-by-default first-2,000-minutes
knob addresses the first and does nothing about the second.

### 2.4 The method families, and what wins

Schuckers et al.'s Table 1 is the standard taxonomy. Writing performance as
`Y_it = g(t) + f(i, t) + ε_it`, where `g(t)` is the population curve and `f(i, t)`
the player effect:

| Family | Examples | Note |
|---|---|---|
| Plain per-age average, `f = 0` | Schulz et al. 1994 | Fails outright; produces upward-sloping tails |
| Fixed effects, delta method | Lichtman 2009, Tulsky 2014 | The standard; §2.3 |
| Fixed effects, quadratic in age | Albert 2002, Bradbury 2009, Fair 2008 | Forces a **symmetric** curve about the peak — structurally unable to express the asymmetry Goalimpact claims. Fair (2008) drops the symmetry assumption for this reason |
| Fixed effects, cubic; extrapolated to unobserved | Brander, Egan & Yeung 2014 (NHL) | One of only two in the table that use unobserved players |
| Semiparametric (splines, GAMs) | Wakim & Jin 2014, Turturo 2019, Judge 2020 | Judge's recommendation; flexible, asymmetric by construction |
| Random effects / individual curves | Berry et al. 1999, Vaci et al. 2019 (NBA), Lailvaux et al. 2014 | Pools individual trajectories |
| Imputation of the unobserved | Schuckers et al. 2021; Nguyen & Matthews 2024 (baseball) | The current frontier |

Their conclusion, from simulation against a known truth:

> It is clear from the results in this paper that the best methods for estimation
> of player aging are those that have model flexibility and that include player
> effects.

and specifically "a spline methodology with fixed player effects" performed best.

### 2.5 Football-specific results

- **Dendir (2016)**, *When do soccer players peak? A note*, Journal of Sports
  Analytics 2:89–105. Four top European leagues, 2010/11–2014/15, WhoScored
  ratings, polynomial regression with fixed and random effects. Peak between 25
  and 27; forwards earliest (~25), defenders latest (~27). Goalkeepers excluded.
- **Sæfvenberg, Nordgaard, Lidmark Eriksson, Carlsson & Lambrix (2024)**, *Age of
  Peak Performance among Soccer Players in Sweden*, ISACE 2024 (Springer). Three
  Swedish tiers, event data, a composite VAEP/xT metric, hierarchical Bayesian
  model with **player-specific age trajectories**. "The results indicate an
  average overall peak age between 25 and 27. Forwards typically peak at 25 […]
  For goalkeepers, the peak generally occurs by age 27. The performance decline
  post-peak is the steepest among forwards and midfielders. Defenders and
  goalkeepers see a long-lasting and slow decline." A 450-minute-per-season filter
  is applied "to filter out players having extreme performances per minute while
  rarely playing" — the same motive as this project's 1,000-minute line. They name
  the selection problem in their own data explicitly: best players are sold
  abroad, and ex-top-league players arrive for their final seasons, "leading to
  potential bias."

So the football consensus on peak age (25–27 for field players) agrees with
Goalimpact's 26–27. **Goalkeeper peak is where the sources diverge sharply**:
Goalimpact says 33; the Swedish study says 27; the Ballon d'Or-proxy study cited
therein says 31. Nothing in the record explains a six-year spread.

### 2.6 The closest published analogue to what this project is building

**Pantuso & Hvattum, *Offensive and Defensive Plus–Minus Player Ratings for
Soccer*, Applied Sciences 10(20):7345, 2020** (open access; also Sæbø & Hvattum,
Journal of Sports Analytics, 2019, which introduced the age treatment).

This matters more than any other citation here, because it is a peer-reviewed
plus-minus rating fitted from *the same inputs this project has* — lineups, goal
times, red cards, dates of birth — that already carries an ageing curve and
already publishes peak ratings. Verbatim:

> The quality of players is assumed to depend on their age, allowing the model to
> capture their typical improvement in early years as well as their decline when
> getting older. Let `t_BIRTH(p)` be the time of birth for player `p`. The age of
> player `p` at the time of match `m` is then `Δ_AGE(m,p) = t_MATCH(m) −
> t_BIRTH(p)` […] **The average effect of age on the ratings of players is
> modelled as a piecewise linear function.** To this end, an ordered set of `k`
> age values `Y = {y_1 = y_MIN, y_2, …, y_k = y_MAX}` is defined. For a given match
> and player, the exact age of the player is expressed as a convex combination of
> the nearest two ages in `Y`.

Their fitted age effect runs over ages 16 to 40 (Figures 5 and 6), separately for
offensive and defensive ratings, with bootstrap confidence intervals that widen at
both ends because "there are few observations of players at the extreme ends of
the age spectrum". Regularisation shrinks the age effect toward zero there — a
principled alternative to simply not drawing the line.

And, decisively for #21:

> This table also indicates **the peak ratings of each player, which are
> calculated based on the age effect curves** […] The three players with the
> highest peak ratings are Lewandowski, Robben, and Ribery. However, since the two
> latter are approaching the mid-30s, their current ratings are adjusted downwards
> accordingly.

That is Peak Impact, published, from a rating model of the same family, on the
same inputs. Two design points are worth carrying into the grill:

1. **Age is a continuous per-match quantity, not a season bucket.** Exact age at
   match date, split as a convex combination between two knots. This sidesteps
   #21's "a year is birthday to birthday" problem entirely rather than solving it:
   there is no year at all, only exact age. Worth putting on the table against
   #21's chained-annual-steps design, which needs the ~900-minutes-in-both-years
   gate and therefore discards short careers.
2. **The age curve is estimated jointly with the ratings, not fitted afterwards
   from a results file.** This is architecturally *not* what ADR 0011 set up, and
   it is the one approach that would need more than the results file (see §3.3).
   Pantuso & Hvattum's ablation study removes the age effect entirely (variation
   B) and reports that it degrades the model — a measured gate of exactly the kind
   CLAUDE.md asks for.

Neither of these is a recommendation; both are options #21 does not currently have
on its list.

---

## 3. Does this project need anything further from the vendor?

#21 states: "unblocked the moment ADR 0011's results file exists — per-match rows
plus dates of birth is exactly the input. Nothing further is needed from the
vendor."

**Confirmed, for the design #21 actually proposes. With one qualification, and one
condition under which it becomes false.**

### 3.1 Confirmed

May's *Goalimpact 1x1* names the original's full input list — "the starting
lineup, goal minutes, player subs, and birthdates of the players" — and this
project already has all of it. Pantuso & Hvattum fit a published ageing curve and
published peak ratings from the same list. Both Dendir and the Swedish study need
*more* than this project has (WhoScored ratings; event data) only because their
performance metric is not a plus-minus derived from lineups. This project's
metric is. Nothing in the literature requires an input this spine lacks.

Concretely, for the chained within-player curve #21 designs, one row needs:
`player_id`, match date, minutes, `rating_after` (Value at that match, already
stored), and `date_of_birth`. ADR 0011's results file plus the snapshot's
`players` table carries every one of them, joined in one DuckDB connection.

### 3.2 The qualification: the 10% is not the 10% you fear, but it is not nothing

ADR 0011 measured it: 90.3% of the written history's 2,352,025 rows resolve to a
date of birth, and of the 17,030 players past 1,000 minutes **15 lack one**. So
the missing decile is almost entirely players who would never have been drawn on
a career chart anyway — cup opposition from clubs the vendor does not track.

For a within-player ageing curve this is benign in a specific and checkable way:
a player with no date of birth contributes **no step at all** to the curve, at any
age. He is missing completely, not misplaced. That is far better than a wrong
birth date, which would misattribute a step.

But it is only benign **if the missingness is unrelated to age**, and there is a
concrete reason to doubt that. The gap is concentrated in untracked lower-division
and cup-appearance clubs. If those clubs' squads skew young (academy graduates,
loanees) or old (end-of-career players dropping down — precisely the pattern the
Swedish study names in its own data), then the missingness is *correlated with the
thing being measured*, and the curve is fitted on a non-random age slice.

**This is cheap to check and should be checked before the curve is trusted**:
compare the age distribution of rows *with* a date of birth against the
competition/tier mix of rows *without* one. It does not need vendor data — the
same snapshot answers it. Nothing in the record suggests this has been done.

### 3.3 The condition under which "nothing further is needed" becomes false

If the age effect is ever moved **inside** the replay — the Hvattum design, where
the age adjustment is a parameter estimated jointly with the ratings, or Seidel's
own 2013 design, where "all the results of all matches will be set in to the
perspective given the age of the players involved" — then **every player on every
pitch needs an age at every match**, including the 10% who currently have none and
including cup opposition that will never be plotted. At that point a missing date
of birth stops being a row that sits out of the curve and becomes a hole in the
model's expectation for a match that is being graded.

Note that this is how the *original* works. Goalimpact's age factor is not a
post-hoc reading of a results file; Seidel introduced it as a factor in the
algorithm. So "nothing further is needed from the vendor" is true of #21 as
scoped, and would need revisiting if the project ever tried to close the gap to
the original's architecture rather than its output.

Even then the remedy is probably not the vendor: the Hvattum treatment censors age
to `[y_MIN, y_MAX]` and regularises the ends, so an unknown age could be handled
by assigning the population-mean age effect (i.e. no adjustment) rather than by
acquiring the data. That is a design choice, not a data blocker. But it is a
choice #21 does not currently record.

### 3.4 One thing #21 should reconsider on its own terms

#21's reading of the second line — "a player's projected ceiling, obtained by
reading his current index against the population's ageing curve" — is the reverse
of Seidel's 2015 description (§1.1). In the original, PeakGI is the estimate and
the current line is derived from it; the ageing curve is baked into the rating
update, so the current rating is *already* age-adjusted and PeakGI is what it is
adjusted *from*.

Building #21 as #21 currently describes it (fit a curve from stored history, then
project each player's current Impact index forward to his peak age) yields a
**defensible object that is not the same object as PeakGI**. It is a projection
laid over an un-age-adjusted rating rather than the latent parameter of an
age-adjusted one. It will look similar on a chart and behave differently — most
visibly, it cannot reproduce Seidel's Zlatan observation ("PeakGI raised because
his performance dropped less than we would have expected"), because that requires
the ageing expectation to be inside the update.

That is a decision for the grill, not for this note. But it should be made
knowingly, and ideally named differently in the glossary if the two diverge.

---

## 4. Where the record is silent

Stated plainly, because filling these in from secondary write-ups would be the
easiest mistake to make here:

- **The functional form of the ageing curve.** No source states whether it is a
  spline, a piecewise linear function, a polynomial, or a lookup table of per-age
  means. Seidel's "average age profile of all non-goalkeepers in the database"
  reads as a non-parametric per-age average, but he does not say so.
- **Any survivorship or selection correction.** Not mentioned in any first-party
  source. May's 2022 essay discusses selection at length — but the *relative age
  effect* in youth recruitment, which is entry selection into football, a
  different thing from decliners leaving the data. He never connects it to how the
  curve itself is fitted.
- **Whether the curve is chained within player or averaged across players.** The
  only wording available ("average age profile of all players in dependence on
  their age", May 2022) points to across-player. If so, the original's curve has
  the very bias ADR 0011 and #21 set out to avoid — but this note cannot establish
  that, only that nothing states otherwise.
- **How PeakGI is initialised for a player with no history**, and how fast it
  moves. Seidel says it rises on over-performance; nothing states the update rule,
  a shrinkage constant, or a prior.
- **Why the stated peak moved from 26 to 27**, or whether the goalkeeper curve
  shares machinery with the field-player curve.
- **The number 26's provenance after 2013.** In 2013 Seidel sources it from
  Transfermarkt market values. Whether the later restatements are independent
  measurements from Goalimpact's own data, or the same 2013 number repeated, is
  not stated.
- **How Peak interacts with the 1,000-minute threshold** — whether a player below
  it has a Peak at all.

Two sources were sought and not obtained: the goalimpact.com blog is entirely
offline (only the Internet Archive has it, and only under the double-slash URL
form `/blog//YYYY/MM/…`), and the Zweierkette interview with Jörg Seidel
("Die beste Liga der Welt?") now 404s with no archive capture found. Neither
appears likely to change the ledger in §1.6, but neither was read.

---

## Sources

Primary — Goalimpact:

- Jörg Seidel, *The Football Aging Curve*, goalimpact.com blog, 15 Dec 2013.
  Live URL dead; read via Internet Archive capture 20200306004714 of
  `http://www.goalimpact.com/blog//2013/12/the-football-aging-curve.html`.
- Jörg Seidel, *How to read a Goalimpact Chart?*, goalimpact.com blog, 12 Apr 2015.
  Live URL dead; read via Internet Archive capture 20200306004714 of
  `http://www.goalimpact.com/blog//2015/04/how-to-read-goalimpact-chart.html`.
- Jörg Seidel, *New Algorithm Released*, goalimpact.com blog, 9 Apr 2015 (source
  of the paired Goalimpact / Peak GI team table). Same archive route.
- Thorsten Wittmütz, interviewed by "timbo", *Timbos kleine Taktikschule — Heute:
  Goalimpact*, Der Übersteiger 130 (10 Dec 2017), online 19 Mar 2018.
  <http://blog.uebersteiger.de/2018/03/19/timbos-kleine-taktikschule-heute-goalimpact/>
- Dr. Patrick May, *How player age (goal)impacts performance*, Moneyball Letters,
  22 Dec 2022. <https://goalimpact.substack.com/p/is-player-age-the-most-important>
- Dr. Patrick May, *Goalimpact 1x1*, Moneyball Letters, 4 Sep 2023.
  <https://goalimpact.substack.com/p/goalimpact-1x1>

Primary — literature:

- M. Schuckers, M. Lopez, B. Macdonald, *What does not get observed can be used to
  make age curves stronger: estimating player age curves using regression and
  imputation*. arXiv:2110.14017. <https://arxiv.org/abs/2110.14017>
- M. Lichtman, *How do baseball players age?* (part 2), The Hardball Times /
  FanGraphs, 2009. <https://tht.fangraphs.com/how-do-baseball-players-age-part-2/>
- J. Judge, *An approach to survivor bias in baseball*, Baseball Prospectus, 2020.
  <https://www.baseballprospectus.com/news/article/59491/an-approach-to-survivor-bias-in-baseball/>
- J. Judge, *The Delta Method, Revisited: Rethinking Aging Curves*, Baseball
  Prospectus, 2020.
  <https://www.baseballprospectus.com/news/article/59972/the-delta-method-revisited/>
- G. Pantuso, L. M. Hvattum, *Offensive and Defensive Plus–Minus Player Ratings for
  Soccer*, Applied Sciences 10(20):7345, 2020.
  <https://www.mdpi.com/2076-3417/10/20/7345>
- O. D. Sæbø, L. M. Hvattum, *Modelling the financial contribution of soccer
  players to their clubs*, Journal of Sports Analytics, 2019.
  <https://journals.sagepub.com/doi/10.3233/JSA-170235>
- S. Dendir, *When do soccer players peak? A note*, Journal of Sports Analytics
  2:89–105, 2016. <https://journals.sagepub.com/doi/10.3233/JSA-160021>
- R. Säfvenberg, A. Nordgaard, O. Lidmark Eriksson, N. Carlsson, P. Lambrix,
  *Age of Peak Performance among Soccer Players in Sweden*, ISACE 2024.
  <https://www.ida.liu.se/~patla00/publications/ISACE24-preprint.pdf>
- Q. Nguyen, G. J. Matthews, *Filling the gaps: A multiple imputation approach to
  estimating aging curves in baseball*, Journal of Sports Analytics, 2024.
  arXiv:2210.02383.

Cited via Schuckers et al.'s Table 1, not read directly: Schulz et al. (1994),
Albert (2002), Berry et al. (1999), Bradbury (2009), Fair (2008), Brander, Egan &
Yeung (2014), Tulsky (2014), Wakim & Jin (2014), Lailvaux et al. (2014), Turturo
(2019), Vaci et al. (2019), Kovalchik & Stefani (2013), Villaroel et al. (2011).

Repo context: [CONTEXT.md](../../CONTEXT.md),
[ADR 0011](../adr/0011-impact-index-and-the-career-chart.md),
[ADR 0009](../adr/0009-transfermarkt-as-the-rating-spine.md), issues #2, #21, #31.
