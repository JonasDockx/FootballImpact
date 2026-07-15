# GoalImpact is an online, career-long rating, not a batch aggregate

GoalImpact is computed as an **online Elo-style rating**: every player carries a
single running rating that is updated after each match they play, by replaying
**all matches across all competitions in global date order** and feeding earlier
results forward into the expectation for later ones. This replaces the original
picture of GoalImpact as a batch quantity aggregated over a fixed dataset (the
flat rule of [ADR 0002](0002-swappable-goal-crediting-rule.md), which summed
credit/blame over one competition).

Each match is a **rating period**: player ratings are frozen at their pre-match
values, every goal in the match produces a per-player residual against those
frozen strengths, and one net update per player is applied at the final whistle
(`r ← r + K·residual`). A goal's residual is `actual − expected`, where the
expected outcome comes from the two lineups' **strength gap** — and a lineup's
strength is the *average of its on-pitch players' current ratings*. This makes
the model **self-referential** (strength is built from the very ratings we are
computing) and resolved **sequentially** rather than by solving a fixpoint.

## Considered options

- **Batch fixpoint (rejected).** Treat all matches simultaneously and iterate to
  a self-consistent equilibrium (frozen-Jacobi passes to convergence). Rejected
  because the intended object is a career rating that *evolves* — later matches
  should build on what earlier ones established — not a single order-independent
  equilibrium. Sequential replay also needs no convergence machinery.
- **External / one-pass strength (rejected).** Weight goals by an independent
  team-strength proxy (FIFA ranking, external Elo). Rejected in favour of the
  self-referential definition so strength reflects the metric itself; kept the
  door open via the swappable rule seam.
- **Per-goal live updates (rejected).** Update ratings after every goal within a
  match. Rejected for the per-match rating period to avoid within-match feedback
  and keep each match reproducible and order-insensitive internally.

## Consequences

- **Ingestion order is irrelevant and recalculation is retroactive.** Ratings
  are recomputed by replaying the full history (sorted by date) each run, so new
  competitions or backfilled *earlier* matches slot into their correct
  chronological position automatically. Order of *adding* data never matters.
- **A later switch to incremental persistence is safe** precisely because the
  per-match update is a pure function of `(pre-match ratings, match)`. Once the
  historical dataset is frozen, one final replay can be checkpointed at date `T`,
  after which only matches with `date > T` are appended. The invariant to
  protect: never let the update depend on hidden global state, or checkpoint mode
  will diverge from full replay. Earlier backfill after `T` invalidates the
  checkpoint and requires recomputing it.
- **Tunable, deferred hyperparameters.** The link function that maps the strength
  gap to an expected probability (its shape and gain `k`) and the update factor
  `K` are left as swappable, empirically-tuned knobs rather than committed now.
- **Cold start is noisy.** Debutants enter at `0` with the same `K` as everyone,
  so a strong newcomer is underrated until matches move them. Accepted for
  simplicity; a provisional-`K` warmup is a possible later refinement.
  *Superseded by [ADR 0006](0006-exposure-based-update-factor.md): `K` is now
  per-player, shrinking with exposure.*
- **Recency is not modelled as decay.** Every match weighs equally; the "recent
  form matters" intuition is captured by ratings *accumulating* over time, not by
  down-weighting old matches.
- **Goals-only signal, behind a seam.** Ratings move only at goal events, so
  goalless matches and clean sheets against strong sides currently carry no
  signal. The residual source is isolated so a time-integrated,
  expected-goal-difference model can replace it without touching the update or
  rating machinery.
- **The metric is no longer a per-90 rate.** Values are accumulated rating
  points, so cross-player comparison no longer needs a minutes threshold the way
  the per-90 leaderboard did (see the revised glossary).
