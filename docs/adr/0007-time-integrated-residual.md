# Residuals are time-integrated: scoreboard jumps minus a continuous expected-goal drain

The residual source no longer reacts only to goals: expected goal difference
accrues continuously over on-pitch time, and a player's residual is **actual
minus expected goal difference over exactly their stints**. The model is built
from **two per-team scoring rates**: a measured **base scoring rate** (the
goals-per-minute of equal lineups) bent multiplicatively by the strength gap —
the stronger side's rate multiplied, the weaker side's divided, by the same
gap-driven factor. Goals contribute their full `±1` scoreboard effect
("jumps"); all expectedness lives in the drain. This makes 0-0s and narrow
losses carry signal: holding a stronger side scoreless is a positive residual
(the real Goalimpact's Plymouth example), winning by less than expected is a
negative one, and the per-goal surprise weighting (`1 − P`) is retired.

Mechanically: the loader translates StatsBomb's per-period restarting clock
into one **continuous playing clock** and emits a new **`MatchEnd`** event
(derived from the `Half End` events we previously discarded), so the engine
finally hears the final whistle; `MatchProcessor` chops each match into
**segments** (lineup-constant stretches) and drives a renamed seam —
**`ResidualSource`**, with `goal(...)` and `segment(...)` callbacks — merging
both kinds of per-player deltas into the existing per-match accumulation. The
update machinery (ratings and exposure frozen at kickoff, `K` applied at the
whistle) is untouched, exactly as ADR 0005 promised.

## Considered options

- **Fixed-pie total (rejected).** Keep expected total goals constant per match
  and let the gap only split it between the sides. Caps expected GD at the pie
  size, so a rout can never be fully expected — strong teams "beat
  expectation" forever and their ratings inflate instead of settling. The
  multiplicative form also predicts mismatches are higher-scoring, which
  matches reality.
- **Additive rate bending (rejected).** The gap adds to one rate and subtracts
  from the other; a large enough gap drives the weak side's rate negative,
  which is meaningless. Multiplying/dividing can never cross zero.
- **Hybrid: keep per-goal surprise and add the drain (rejected).** Subtracts
  expectation twice — a textbook expected 2-0 win would net a *negative*
  residual. Only "full jumps, all expectation in the drain" makes expected
  performance ≈ zero residual, the anchor the whole system hangs on.
- **Engine-owned period handling (rejected).** Emitting `PeriodEnd` events and
  letting the engine pause/resume clocks spreads StatsBomb's storage
  convention (the second half restarts at minute 45) into the engine — the
  messy source knowledge ADR 0004 confines to the loader — and makes "never
  subtract timestamps across periods" a permanent trap for future code.
- **Tuning the base rate in the grid (rejected).** The who-scored log-loss
  sees only the *ratio* of the two rates, so the base rate cancels out of it
  exactly — the grid would tune on noise. It is measured instead.
- **Rule-owned replay / two separate seams (rejected).** Handing the rule the
  whole event list duplicates the on-pitch state machine; a separate drain
  seam next to `CreditRule` splits one model across two objects that must
  secretly agree on link and base rate.
- **Man-count-aware rates now (deferred, backlog item 13).** Strength is
  count-invariant, so 10 men are expected to perform as 11 for every remaining
  minute. Accepted: rare, bounded, not a new blindness (the goals-only rule
  had it at goal instants), and `segment(...)` already exposes the set sizes,
  so the later fix needs no structural change.
- **Score-aware rates (deferred, backlog item 12).** The drip has no memory of
  the current score; real teams manage leads. Same acceptance.

## Consequences

- **Every match now carries signal, 0-0s included.** No match is invisible to
  the ratings anymore; residual units change from per-goal surprise to
  goal-difference units and arrive from every match.
- **`(K0, H)` must be re-tuned; `k` stays pinned at 0.10.** The link gain is
  inherited as the who-scores logistic gain, so the harness keeps measuring
  the same curve; the floor stays at 0.05, still never binding, still
  empirically untested (see ADR 0006).
- **The base scoring rate is a measured calibration constant** — goals ÷
  team-minutes over the male dataset on the honest clock — hardcoded and
  documented next to `k`. **Re-measure and overwrite it whenever a large
  batch of new competitions/eras lands**; per-era or per-competition rates are
  the deeper refinement if one global average proves too coarse.
- **The clock fix retroactively changes exposure** (honest minutes, final
  whistle heard — today the engine closes time at the last loaded event),
  shifting ratings before the new model even lands. Hence the **staged
  landing**: (1) clock fix under the old rule, re-baseline the log-loss;
  (2) measure the base rate; (3) `ResidualSource` refactor with
  provably identical outputs; (4) the new model, grid re-tune, gate.
- **Ship gate: parity-or-better log-loss against the re-baselined old rule,
  plus named-match demonstrations** of the new capability — a weak side
  holding or narrowly losing to a strong side collects positive residuals, a
  too-narrow favourite win is docked, top-20 eyeball. Parity is acceptable
  (unlike ADR 0006's strict gate) because the point is a capability the
  conditional log-loss structurally cannot see directly: it only judges goals
  that happened, and the clean-sheet signal reaches it only as better-informed
  ratings.
- **Old rules stay expressible** behind `ResidualSource` via an empty
  `segment` default — the flat rule and the goals-only surprise rule remain
  runnable for honest A/B comparison on the same engine and clock.
- **Partially supersedes [ADR 0005](0005-online-career-rating.md):** its
  "goals-only signal, behind a seam" consequence is that seam being cashed in.
- **Glossary updated:** *Residual*, *Credit / Blame*, and *Link function*
  redefined; *Base scoring rate*, *Segment*, and *Expected goal difference*
  added (the last with an explicit warning that it is unrelated to shot-based
  xG).
- **Tuning and gate outcome (2026-07-15): shipped.** Measured base rate
  **0.01473 goals per team-minute** (7,496 goals / 509,022 team-minutes on
  the honest clock). The re-tuned winning cell is **`K0 = 1.0`, `H = 4,000`**
  minutes, interior on both axes — `K0` halved from the goals-only era,
  exactly because every match now carries signal. Quantitative gate:
  log-loss **0.6326 vs 0.6331** goals-only baseline — a genuine improvement,
  not mere parity. Qualitative gate: England 0-0 Slovenia (invisible to the
  goals-only rule) pays every Slovenia player (+0.66 for full-match starters,
  +0.31 for high-exposure Oblak, slivers for late subs) and docks every
  England player (Guehi −0.66, veteran Kane only −0.28); Georgia 2-0 Portugal
  pays ≈ +2.5 (the +2 jumps plus the ~0.5 drain Georgia survived), with
  Ronaldo docked least (−0.95, high exposure). Most visible leaderboard
  shift: defenders rise (Puyol into the top 10, Thiago Silva, Ramos, Umtiti
  up) — clean sheets finally pay.
- **Re-measured on the Transfermarkt spine (2026-07-22).** This ADR's
  instruction to re-measure "when large new eras/competitions land" came due
  with [ADR 0009](0009-transfermarkt-as-the-rating-spine.md)'s full ingest:
  **0.01532 goals per team-minute** — 223,810 goals over 14,610,840
  team-minutes, from 80,471 matches spanning 2013-07-02 to 2026-07-06 across
  65 competitions. Higher than StatsBomb's 0.01473 for two reasons at once: a
  broader, less showcase-skewed population, and a nominal clock whose
  denominator counts no stoppage time (ADR 0009). `K0 = 1.0` and `H = 4,000`
  were re-confirmed **unchanged** on the new spine by an 81-cell grid, each
  interior on its sweep.
- **The base rate is now per spine, not one global constant.** It is a
  property of a *population* — how often goals happen in a particular set of
  matches — not of the model, and nothing about it is tuned. One shared
  constant would have silently moved the pinned StatsBomb run the moment the
  new number landed, destroying the regression check that proves the old path
  is undisturbed. StatsBomb keeps 0.01473; Transfermarkt carries 0.01532.
