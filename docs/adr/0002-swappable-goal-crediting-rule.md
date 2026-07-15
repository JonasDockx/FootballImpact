# Goal-crediting is a swappable rule

The rule that turns a goal into per-player credit/blame is isolated behind an
interface (strategy), not hard-coded into the data pipeline.

We start with a flat rule (`+1` to every on-pitch player on the scoring team,
`−1` on the conceding team) because it matches the initial spec and is trivial to
reason about while learning. But the stated destination is a weighted model that
adjusts for teammate/opponent strength. Keeping the crediting rule as a seam
lets us evolve from flat to weighted without rewriting the pipeline that
reconstructs who was on the pitch.
