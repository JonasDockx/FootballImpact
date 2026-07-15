# Home advantage shifts the effective strength gap; the loader decides who is at home

Home advantage enters the model as one constant **h** in rating units: wherever
the rule computes the strength gap, the **home side**'s effective gap is
shifted by `+h`, flowing identically into the who-scores probability
(`logistic(gain·(gap+h))`) and the expected-goal-difference drain. Ratings
themselves stay venue-neutral — h is match context, applied at kickoff and
gone at the final whistle, never written into anyone's rating.

Who *is* the home side is a per-match conclusion — the labeled home team, the
labeled away team, or nobody — computed by the **loader**, because the
evidence is source knowledge (ADR 0004): StatsBomb stamps an administrative
`home_team` on every match, including tournaments where it means "listed
first" (Germany, *host* of Euro 2024, was labeled away twice). The StatsBomb
rule is a **two-world rule**:

- **Domestic competitions** (`country_name` is a real country): the labeled
  home team — except *single-match final stages* ("Final",
  "Championship - Final": 3 Copa del Rey finals, the ISL Championship Final,
  the NASL Soccer Bowl — all at neutral grounds) and the **ISL 2021/22 bubble
  season** (115 matches in 3 stadiums, no travel, no crowds — every label
  fiction), both pinned to *no home side*.
- **Cross-border competitions** ("Europe", "International", …): a side is
  home **iff its country equals the stadium's country**. This pays Euro
  2020's 27 own-country matches (England at Wembley through the final),
  restores Euro 2024 host Germany despite its away labels, catches Bayern's
  2012 Champions League final at their own stadium, handles two-legged
  European ties (the 1989 UEFA Cup final's home legs stay home), and leaves
  genuinely neutral matches (Qatar 2022, most CL finals) with nobody at home.

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

- **Trust the label everywhere (rejected).** Hands a fake boost to whichever
  tournament team is listed first and denies real hosts theirs by coin-flip —
  the exact noise injection item 14 exists to prevent.
- **Tournaments all neutral (rejected).** Throws away real signal: 27 of Euro
  2020's 51 matches had a side in its own country, and tournament host
  advantage is among the best-documented HFA effects.
- **Hardcoded per-competition venue table (rejected).** A maintenance burden
  on every new data drop that still needs the country test anyway for
  multi-host tournaments and hosts.
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
  differ, but the thin slices (3 Copa del Rey matches, 21 CL finals across
  50 years) invite overfit; global-constant-first mirrors the base rate.
  Per-era/per-competition h is the named refinement if distortions show.
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
  evidence, so its signature takes the `Match` (or the effective home team).
- **Every future data source must answer "who is at home" from its own
  evidence** — the two-world rule is the *StatsBomb loader's* implementation,
  not the domain's. The domain concept is only: home side ∈ {home team, away
  team, nobody}.
- **Known limitation:** a same-country European tie (all-Spanish CL
  semi-final at the Bernabéu) defeats the country test — both sides match the
  stadium country, so it degrades to *no home side*. Zero such matches exist
  in current data; the named upgrade is a club→home-stadium map inferred from
  domestic matches.
- **h is a population average** over eras, competitions, and ~90 ghost
  matches. It slightly under-boosts crowded modern venues and over-boosts
  empty ones; revisit only if an attendance-carrying source lands.
- **Staged landing, mirroring ADR 0007:** stage 1 wires classification,
  `Match` field, `StartingXI` flag, and the `Lineup` seam with `h = 0` —
  gate: byte-identical CSV, log-loss 0.6326 unchanged, classification stats
  printed per competition. Stage 2 adds h to the grid — gate: **strictly**
  beat 0.6326 (h targets the measured quantity directly, so ADR 0007's
  parity concession does not apply), winner in the anchor's vicinity, and
  named-match demos: a home 1-0 pays less than the same away 1-0; Germany's
  Euro 2024 host matches receive h while Portugal-in-Leipzig stays a neutral,
  unchanged control.
- **Glossary updated:** *Home side* and *Home advantage* added; both defined
  free of any data-source detail.