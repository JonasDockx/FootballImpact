# The update factor is per-player, shrinking with exposure

The update factor `K` is no longer one global constant: each player's update is
scaled by a **deterministic function of their exposure** (career minutes on
pitch), fading smoothly as `K(m) = K0 · H / (H + m)` — `H` is the exposure at
which updates halve — down to a **floor** expressed as a fraction of `K0`.
Under uniform `K`, a 47,000-minute veteran's rating swung as hard per match as
a debutant's (Messi losing ~2 points in one upset is noise, not signal); the
real Goalimpact anchors the same idea in exposure terms (~1,000 minutes before
a rating means anything, ~100 games ≈ "relatively reliable"). The exposure used
to size `K` is **frozen at kickoff**, exactly like ratings — the rating period
stays exception-free. The schedule lives behind its own seam (a small class,
like the link function), so uniform `K` remains expressible and richer models
can replace it without touching the update machinery.

## Considered options

- **Explicit per-player uncertainty state (deferred, named upgrade path).** A
  Glicko/Kalman-style σ that shrinks on updates and can grow again. Rejected
  for now as more state and semantics than the diagnosed problem needs; it is
  the designated successor if the schedule proves too crude — specifically if
  a tuned floor cannot track late-career decline, or inactivity handling turns
  out to matter. Both live behind the same seam.
- **Two-step / provisional K (rejected).** Big `K` until a minutes threshold,
  small `K` after. Creates an arbitrary cliff (999 vs 1,001 minutes).
- **Exponential fade (rejected).** Halves every fixed number of minutes; races
  to zero and freezes long careers outright.
- **No floor (rejected).** `K → 0` means a veteran's rating effectively
  freezes, making late-career decline invisible — the floor is what lets months
  of underperformance still drag an aging player down, at a tunable speed.
- **Inactivity widening (rejected).** Raising `K` again after a long gap. In
  our patchwork dataset (scattered competitions), absence from *our* matches
  almost never means the player wasn't playing — widening would punish data
  sparsity, not true inactivity.
- **Match-level shared K (rejected).** Forcing both sides to one `K` per match
  would preserve zero-sum conservation but guts the point: a debutant playing
  veterans would learn as slowly as they do.
- **Population re-centering (rejected).** Restoring conservation by shifting
  everyone back to mean 0 makes ratings move for players who were nowhere near
  the match.

## Consequences

- **Conservation is given up.** With per-player `K`, one side's gain no longer
  equals the other's loss, so the population total can drift. Accepted: only
  rating *gaps* are meaningful (the link function never sees absolute levels),
  drift is monitorable via the log-loss harness, and any cosmetic re-anchoring
  belongs in the display scale (backlog item 8), not the engine. The glossary's
  *Value* entry no longer promises conservation.
- **Partially supersedes [ADR 0005](0005-online-career-rating.md).** Its
  "cold start is noisy, uniform `K`, provisional-`K` warmup possible later"
  consequence is that warmup, realized — and generalized to whole careers.
- **Strength is unchanged.** Lineup strength stays the plain average of
  current ratings; debutants count at face value. Their `0` is the deliberate
  cold-start prior, and the error self-corrects — an undervalued lineup makes
  wins surprising, and the debutant's large `K` converts those residuals into
  fast catch-up. Certainty-weighted strength is a separate future experiment
  behind the `strength()` seam.
- **Exposure must be snapshotted at kickoff** alongside the frozen ratings.
  Reading accumulated minutes at the final whistle is wrong: substituted
  players' minutes land mid-match, full-90 players' land after the update
  loop, so `K` would be inconsistent within one match.
- **Tuning and ship gate.** One coarse grid over (link gain `k`, `K0`, `H`,
  floor fraction), with `H` anchored around the Übersteiger numbers. Uniform
  `K` is embedded in the grid as floor fraction = 100%, so the comparison is
  apples-to-apples: adaptive `K` ships only if it beats the best uniform
  log-loss. Caveat: part of the motivation is cosmetic sanity (headline
  ratings should be a slow, smooth curve), so "log-loss equal, leaderboard
  much saner" is read as a win — eyeball top-20 volatility alongside the
  number.
- **Tuning outcome (2026-07-14): `k` and `K0` are redundant — only their
  product matters.** Predictions depend on the strength gap solely through
  `k·gap`, so scaling every rating by `k` is invisible: (k, K0) grids are a
  one-dimensional family in `c = k·K0` (grid cells duplicated to four decimal
  places confirmed this empirically). `k` is therefore **pinned at 0.10** and
  `K0` alone carries tuning — do not re-add a `k` dimension to the grid. The
  winning cell: `K0 = 2.0` (c = 0.20), `H = 4,000` minutes, interior on both
  axes; gate passed at log-loss 0.6331 vs 0.6335 best-uniform. The floor never
  binds within observed careers (the fade reaches `0.02·K0` only past
  ~196,000 minutes), so its tuned value is arbitrary today and the
  late-career-decline concern the floor exists for remains empirically
  untested.