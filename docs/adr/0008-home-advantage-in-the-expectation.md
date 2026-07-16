# Home advantage shifts the effective strength gap; the loader decides who is at home

Home advantage enters the model as one constant **h** in rating units: wherever
the rule computes the strength gap, the **home side**'s effective gap is
shifted by `+h`, flowing identically into the who-scores probability
(`logistic(gain·(gap+h))`) and the expected-goal-difference drain. Ratings
themselves stay venue-neutral — h is match context, applied at kickoff and
gone at the final whistle, never written into anyone's rating.

Who *is* the home side is a per-match conclusion — the labeled home team, the
labeled away team, or nobody — computed by the **loader**, because the
evidence is source knowledge (ADR 0004). The StatsBomb rule forks on
`competition_international`, i.e. on **national teams vs clubs**:

- **National-team competitions** (World Cups, Euros, Copa America, AfCon):
  the `home_team` label is administrative — Germany, *host* of Euro 2024, was
  labeled away twice — so geography decides: a side is home **iff its country
  equals the stadium's country**. This pays Euro 2020's 27 own-country
  matches, restores mislabeled hosts, and leaves genuinely neutral matches
  (Qatar 2022) with nobody at home. No stadium in the row → no evidence → no
  home side.
- **Club competitions** (domestic leagues *and* European cups alike): the
  fixture label marks someone's genuine home fixture — Napoli's 1989 UEFA Cup
  home legs are as real as any La Liga Saturday — so **the label is trusted**,
  except:
  - **single-match final stages** ("Final", "Championship - Final"): one
    match at a chosen ground is nobody's home fixture (Copa del Rey finals,
    the Soccer Bowl, every modern Champions League final);
  - **curated source facts**, a small documented mechanism of last resort:
    `NEUTRAL_SEASONS` (ISL 2021/22 — the whole season in a COVID bubble, 115
    matches in 3 stadiums, every label fiction) and per-match
    `HOME_SIDE_OVERRIDES` checked before every rule (Napoli–Stuttgart
    1989-05-03: the *two-legged* final's first leg, a genuine home fixture
    the finals rule would wrongly neutralise).

The conclusion lands on `Match`, travels into the event stream as a
`boolean home` on `StartingXI` (at most one per match, loader tripwire
enforced), and reaches the rule through a deepened seam: both `ResidualSource`
methods take **`Lineup(Set<Player> players, boolean home)`** instead of bare
player sets, so home-ness travels with the side it describes and the sides
stay order-free. h is calibrated in the grid — unlike the base scoring rate
it does *not* cancel out of the who-scored log-loss, so the harness is the
right instrument — with `h = 0` embedded as the baseline cell and an
independent sanity anchor printed: the home share of goals in genuine-home
matches, converted via `ln(homeGoals/awayGoals)/gain`.

## Considered options

- **Country test for clubs too (shipped first, then rejected).** The original
  design applied the geography test to every cross-border competition. Review
  of the flagged matches killed it: it made Manchester United "home" at the
  2011 Wembley final and Ajax "home" at the 1972 De Kuip final — being *in
  your country* at someone else's stadium is not home advantage — while
  degrading Napoli's genuine home leg against Juventus (same-country tie) to
  neutral. The country test is a *host-nation* test; only national teams have
  host nations. Clubs have grounds, and their fixture labels name them.
- **Trust the label everywhere (rejected).** Hands a fake boost to whichever
  tournament team is listed first and denies real hosts theirs by coin-flip —
  the exact noise injection item 14 exists to prevent.
- **Tournaments all neutral (rejected).** Throws away real signal: 27 of Euro
  2020's 51 matches had a side genuinely in its own country, and tournament
  host advantage is among the best-documented HFA effects.
- **Hardcoded per-competition venue table (rejected).** A maintenance burden
  on every new data drop. The adopted curated-facts mechanism is deliberately
  narrower: the *rules* stay derivable from the data; curation is reserved
  for individually documented exceptions the data itself cannot express.
- **Pinning Bayern's 2012 "Finale dahoam" as home (rejected).** The final at
  Bayern's own Allianz Arena: venue familiarity was real, but final crowds
  are ticket-allocated roughly half-and-half, and one match cannot calibrate
  a reduced-h. Untested advantage → neutral, consistently applied.
- **Rate-multiplier parameterization (rejected).** `home×m, away÷m` is the
  same curve today (`m = e^(gain·h/2)`) but lives in rate space: swap the
  link function and the calibrated constant stops meaning anything. The gap
  offset is link-independent and reads in rating points.
- **Who-scores only, drain unchanged (rejected).** Improves the measured
  log-loss while leaving the residuals — the thing that moves ratings —
  systematically biased; splits one model in two (the hybrid ADR 0007
  rejected).
- **Sign parameter or home-aware `RatingLookup` instead of `Lineup`
  (rejected).** The sign parameter reintroduces positional side-coupling the
  seam's contract forbids; wrapping the lookup makes "a rating" mean two
  things and moves h's value into the engine, which owns no model knobs.
- **Passing `Match` into `process()` (rejected).** Breaks the engine's
  replay-is-a-list-of-events shape (ADR 0003) and grows every call site and
  test for one bit of information.
- **Measuring h directly like the base rate (rejected).** The base rate is
  measured *because* the log-loss is blind to it; h is visible to the
  log-loss in nearly every match, and only a full prequential replay captures
  the feedback loop from better expectations through ratings to later
  predictions.
- **Per-competition h (rejected).** League and tournament HFA genuinely
  differ, but the thin slices invite overfit; global-constant-first mirrors
  the base rate. Per-era/per-competition h is the named refinement if
  distortions show.
- **Pinning COVID ghost matches neutral, or half-h (rejected).** The ~90
  crowd-free matches (La Liga 2020/21, the 2019/20 restart tail, Euro 2020's
  reduced capacities) were at genuine home venues — calling them neutral
  corrupts the glossary term, and a ghost factor is a second constant fit on
  less data than any slice per-competition h was refused for. Accepted as
  calibration noise (<4% of matches, documented drag on h).

## Consequences

- **The seam deepens once, for three items.** `Lineup` is the natural landing
  spot for item 11's goalkeeper flag and already exposes item 13's man count
  (`players().size()`).
- **`loadEvents` needs the conclusion** — the events file carries no venue
  evidence, so its signature takes the `Match`.
- **Every future data source must answer "who is at home" from its own
  evidence** — the national-teams-vs-clubs rule is the *StatsBomb loader's*
  implementation, not the domain's. The domain concept is only: home side ∈
  {home team, away team, nobody}.
- **Label-trust closes the old same-country gap.** A future all-Spanish
  Champions League semi at the Bernabéu is handled correctly by its label; no
  club→home-stadium map is needed. What label-trust cannot see — a club
  match at a neutral ground that isn't a final-stage match, or the odd
  genuinely-neutral oddity — is exactly what the curated-facts escape hatch
  is for.
- **h is a population average** over eras, competitions, and ~90 ghost
  matches. It slightly under-boosts crowded modern venues and over-boosts
  empty ones; revisit only if an attendance-carrying source lands.
- **Staged landing, mirroring ADR 0007:** stage 1 wired classification,
  `Match` field, `StartingXI` flag, and the `Lineup` seam with `h = 0` —
  byte-identical CSV, log-loss 0.6326 unchanged, classification stats
  printed per competition. Stage 2 added h to the grid under a **strict**
  gate (h targets the measured quantity directly, so ADR 0007's parity
  concession did not apply).
- **Glossary updated:** *Home side* and *Home advantage* added; both defined
  free of any data-source detail.
- **Tuning and gate outcome (2026-07-16): shipped.** Home sides scored
  **56.7%** of the 6,363 goals in genuine-home matches → anchor
  `h ≈ 2.69`. Grid winner **h = 2.5** (interior on a 0–4 sweep; `K0 = 1.0`,
  `H = 4,000` reconfirmed in a 27-cell cross-check), log-loss **0.6259 vs
  0.6326** venue-blind baseline — the largest single improvement since
  tuning began (adaptive K bought 0.0004, time-integration 0.0005, venue
  0.0067). Implied venue edge between equal sides: ~0.35 goals of expected
  GD per match, inside the literature's 0.3–0.5. Named-match demos: Thomas
  Müller's late, goalless cameo in Germany's 5-1 *home* rout of Scotland
  nets −0.19 (a strong home side is expected to keep scoring), while two
  late Scotland subs finish *positive* by surviving their stints; England
  0-0 Slovenia (neutral) reproduces ADR 0007's demo almost exactly — the
  control untouched; Real Madrid 0-4 Barcelona docks Real −2.6..−3.6 for a
  home collapse and pays Barcelona a rout achieved against the venue.
  Leaderboard: home-heavy overperformers drop (Zlatan, Cavani), away
  performers rise (Pepe to #2).