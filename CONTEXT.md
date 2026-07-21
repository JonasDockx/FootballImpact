# GoalImpact

A learning-oriented Java project that computes each football player's net goal
contribution *while they are on the pitch*, inspired by the GoalImpact metric.

Ratings are computed from one source at a time — a *spine*. The spine is
Transfermarkt; StatsBomb open data is kept as test fixtures and as the
calibration set the spine is measured against, never pooled into a rating run
(see [ADR 0009](docs/adr/0009-transfermarkt-as-the-rating-spine.md)). Player
identity and aggregation are keyed on the spine's own stable player ID and
must combine stints across every competition it covers.

## Language

**GoalImpact**:
The project, and the per-player metric it produces: a player's running rating of
their net goal contribution accrued only while on the pitch, updated after every
match they play and carried forward across their whole career (see
[ADR 0005](docs/adr/0005-online-career-rating.md)).
_Avoid_: score

**On pitch**:
The state of a player actively participating in a match. A player is on pitch
from kickoff (if in the starting XI) or from the minute they are substituted on,
until they are substituted off, sent off, or the match ends.

**Stint**:
A single contiguous interval during which a player is on pitch in one match. A
player who is never subbed has one stint spanning the whole match.
_Avoid_: spell, shift

**Spine**:
The single source a rating run draws its matches from. Exactly one per run: the
same match arriving under two sources' identities would be replayed twice,
inflating exposure and double-counting residuals. Other sources may still
supply reference facts, or serve as a calibration set the spine is measured
against, without ever entering a run.
_Avoid_: pooling sources

**Playing clock**:
The single time axis a match's events sit on: seconds of play since kickoff,
running continuously across halves rather than restarting each one. Nominal by
convention — a half is 45 minutes however much stoppage was actually played,
an extra-time half 15 — because the base scoring rate is measured against the
very same denominators, so the convention cancels out of the expectation.
_Avoid_: real time, elapsed time, wall clock

**Segment**:
A stretch of one match during which neither lineup changes — cut at kickoff,
substitutions, red cards, and the final whistle. Goals do not cut segments. The
unit over which expected goal difference accrues.

**Goal**:
A scoreboard-changing goal that triggers credit/blame. Includes open-play goals,
open-play penalties, and own goals (counted by scoreboard effect — the
beneficiary team is credited, the conceding team blamed, regardless of who
touched it last). Excludes penalty-shootout goals and disallowed/VAR-ruled-out
goals.

**Strength**:
A lineup's rating at a moment in a match: the average of the current GoalImpact
ratings of that team's on-pitch players. Emergent from players — there is no
separate team rating. Count-invariant, so a red-carded side keeps roughly its
level.

**Home side**:
The side of a match, if any, genuinely playing at its own venue. At most one
side is ever at home; a match on neutral ground has no home side. A side is at
home in a fixture it was scheduled to host — which the fixture's home label
names correctly in club football and in international qualifying alike, except
for one-off finals at a chosen ground and seasons played wholesale at neutral
venues. Where an entire competition is instead played at a chosen host, the
label is administrative and names nobody: only a side of the host country is
at home, and at a host that is nobody's country, no one is.
_Avoid_: home team — that names the administrative label, not the concept

**Home advantage**:
The strength bonus the home side enjoys for the duration of a match: a
constant that shifts the effective strength gap in the home side's favour,
leaning the who-scores probability and the expected-goal-difference drain
toward them alike. Pure match context — applied at kickoff, gone at the final
whistle, never written into any rating, so ratings stay venue-neutral. An
empirically calibrated constant.
_Avoid_: HFA (in code and docs)

**Credit / Blame**:
The signed value a goal assigns to each on-pitch player — positive (credit) on
the scoring team, negative (blame) on the conceding team: the goal's full `±1`
scoreboard effect. A goal's "expectedness" lives entirely in the
expected-goal-difference drain, never in the goal's value itself.

**Residual**:
`actual − expected`, accumulated over a player's on-pitch time: the
scoreboard's actual goal difference (full `±1` jumps at goals) minus the
expected goal difference that drains continuously from the strength gap.
Holding a stronger side scoreless yields a positive residual; winning by less
than expected yields a negative one. Residuals are the signal that moves
ratings.

**Expected goal difference**:
The goal difference the strength gap predicts over a stretch of play: each
side's scoring rate times minutes, differenced. Accrues continuously with the
playing clock and is fractional by nature ("wir arbeiten im
Nachkomma-Bereich").
_Avoid_: xG — shot-quality-based expected goals is an unrelated concept from
football analytics; ours derives from lineup strengths, not shots

**Base scoring rate**:
The scoring rate (goals per minute of playing time) of either side in a match
between equal-strength lineups — the anchor the strength gap bends. A
*measured* calibration constant (goals ÷ team-minutes over the dataset), not a
tuned knob; re-measured when large new eras or competitions land.

**Link function**:
The mapping from the strength gap to the two lineups' expected scoring rates:
the base scoring rate multiplied up for the stronger side, divided down for
the weaker, by a gap-driven factor. "Given a goal, who scored it?" falls out
as the stronger side's share of the combined rate — a logistic curve in the
gap. Shape and gain are swappable and tuned empirically.

**Update factor**:
The per-player multiplier that turns a match's summed residuals into a rating
change. It shrinks smoothly as exposure grows — a debutant's rating moves most,
a veteran's least — but never below a floor, so every rating can always still
move. Frozen at its pre-match value, like ratings.
_Avoid_: learning rate, K-factor

**Exposure**:
The total minutes a player has spent on pitch across their whole career — the
measure of how much evidence the model has about them. Drives the player's
update factor: the more exposure, the less one match moves the rating.
_Avoid_: experience, sample size, games played

**Goalkeeper**:
A player who has ever appeared in a Starting XI at the goalkeeper position — a
career-level tag, permanent once earned. An emergency keeper (a field player
finishing a match in goal) is not a Goalkeeper.
_Avoid_: keeper (in code and docs), GK

**Rating period**:
One match. Every player's rating is frozen at its pre-match value for the whole
match; the match's residuals are accumulated per player and applied as a single
update at the final whistle, so ratings never shift mid-match.

**Left-censored career**:
A career that began before the earliest match a run covers. Its opening ratings
show the model discovering the player, not the player developing — a rise on
the age curve that is an artefact of where the data starts. Derived per run
(first seen at the window's leading edge), never stored, so backfilling earlier
matches reclassifies it by itself.
_Avoid_: cold start — that names the model's condition, not the career's

**Value**:
A player's current GoalImpact rating — an accumulated point total, not a
per-match or per-90 average. Population totals are not conserved (per-player
update factors let one side gain more than the other loses), so only rating
*gaps* between players and lineups are meaningful, never absolute levels.
