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

**Sidecar**:
The store of hand-made facts kept beside the vendor snapshot — match repairs,
matches authored from nothing, curated home-side facts. It holds only
*decisions*, never problems: the set of unusable matches is recomputed from the
gate on every run, not saved. A match present here and *released* replaces the
vendor's copy of that match outright, wholesale rather than field by field (see
[ADR 0009](docs/adr/0009-transfermarkt-as-the-rating-spine.md)). Precious —
never auto-wiped when the vendor snapshot is refreshed.
_Avoid_: patch, override — a sidecar match is a whole-match replacement

**Match state**:
Which of three conditions a match is in for a run, and whether it moves ratings.
**Clean** — passes the usability gate, so it rates automatically on vendor data.
**Held** — fails the gate and has not been released, so it is ingested and
listed but moves no rating. **Released** — checked and approved by hand in the
*Sidecar* (possibly after repair), so it rates on the sidecar's copy. A rating
moves only on a Clean or Released match; nothing half-broken is ever trusted.
The gate is never loosened to auto-admit imperfect data, and Held is recomputed
every run, never stored.
_Avoid_: skipped — a Held match is kept and visible, not dropped

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

**Bridge**:
A match whose two sides are rated in different pools — a cross-division cup tie,
a European fixture, a national-team match. Rating levels are only comparable
across pools through bridges: a pool that plays only itself is zero-sum and its
average is pinned near 0 whatever its true level. A division added without its
bridges therefore floats at a false average, which is why width is not purely
additive — some additions make the ratings worse (see #9).
_Avoid_: link, connector

**Unpriced club**:
A club a run never sees play league football, so it never watches the club
against a pool it knows and never learns what the club is worth. Its players sit
at 0, which this model reads as *exactly world-average* rather than *unknown* —
so a cup minnow's eleven arrive priced as the equals of whoever draws them.
2,074 of 2,867 clubs on the 85,050-match spine, 11.4% of appearances, and 58.7%
of all debuts. A fact about **coverage**, not about results: it says which
competitions the run happens to carry, never how anyone did in them. A national
side plays no league either and is nobody's minnow, so it is never unpriced.
Distinct from an *Island*, which the run sees plenty of and still cannot place.
_Avoid_: unrated club, unknown club — every club's players carry ratings; what
is missing is evidence about their level

**Rating seed**:
The rating a player enters a run at, the first time it is ever seen him — 0 for
everyone except a player first seen at an *Unpriced club*, who enters below
average by a measured, pinned constant (see
[ADR 0015](docs/adr/0015-seeding-a-rating-below-average.md)). Set once, at the club he
debuted for, and never revisited: a player who later signs for a league club
keeps the career he has. Strength stays emergent from players — nothing
club-level is stored or updated, and the club decides only where a rating
*starts*.
_Avoid_: prior, initial rating — the seed is one number at one moment, not a
distribution and not a per-match adjustment

**Island**:
A pool with too few bridges for its level to be established, so its players are
priced against each other rather than against football. Scotland is the measured
example: it gave Scott Brown +122.9 index points that the rest of his career
took only 35.0 back.

**Spine width**:
Which competitions and which seasons the spine covers — the finish line for the
data layer, deliberately frozen rather than open-ended (see
[ADR 0013](docs/adr/0013-spine-width.md)). Distinct from the spine itself, which
is *which source* a run draws from; width is *how much of it*.
_Avoid_: coverage — that names how complete a competition-season is, not which
ones are in

**Scoring window**:
The stretch of a run over which predictions are *graded*, which need not be the
stretch that is *replayed*. Everything is always replayed, in date order, every
rating still reading only matches before it; the window decides only which
predictions count toward the run's log-loss. It exists so that the burn-in at a
run's leading edge — where every rating is still near zero, so every prediction
is near even, whatever the knobs are — does not decide which knobs win (see
[ADR 0010](docs/adr/0010-scoring-window.md)). A pinned constant with a stated
reason, never a tuned one.
_Avoid_: training window, test set — nothing is held out and nothing is fitted

**Designated run**:
The one replay whose numbers the project stands behind — the full ingest over
every competition-season, at the pinned constants, and the run every calibration
was measured on: the base scoring rate, *h*, `K0`, `H` and the champion
log-loss. Only it writes the results file; a grid cell never does. Re-running it
is a deliberate, dated act, because a champion log-loss belongs to a population
and re-measuring is what moves the record.
_Avoid_: the main run, production run — what marks it is that the constants were
measured on it, not that it is the usual one

**Refresh run**:
A replay whose only purpose is to carry newly scraped matches through to the
viewer: fixed constants, no tuning grid, no re-measurement. It is what the weekly
job (#27) is allowed to do unattended, and the distinction from a *Designated
run* is the whole point — refreshing the data and re-measuring the model are two
different acts, and only the first happens while nobody is watching. Its output
is the same results file plus a rebuilt viewer; what it must never do is report a
new champion.
_Avoid_: incremental run — nothing is incremental, the whole spine is replayed
every time; only the grid is skipped

**Viewer**:
The generated HTML page carrying every eligible player's career chart, built
from the results file and the vendor snapshot by a step that replays nothing.
Its *template* is repo source and hand-written; only the filled copy is
generated, and the copy is disposable like the results file it reads. It states
the run it was built from and that run's last match date, and deliberately not
the time it was built — a build clock would read "today" over a months-old
history, which is the one staleness a separate build step risks.
_Avoid_: the chart, the site — the viewer is one file, and the chart is what it
draws

**Value**:
A player's current GoalImpact rating — an accumulated point total, not a
per-match or per-90 average. Population totals are not conserved (per-player
update factors let one side gain more than the other loses), so only rating
*gaps* between players and lineups are meaningful, never absolute levels.

**Impact index**:
A player's Value placed on the population scale where the average player is 100
(see [ADR 0011](docs/adr/0011-impact-index-and-the-career-chart.md)) — a linear
rescale, and the only form in which a rating may be quoted as an absolute
number. Value converges rather than accumulating, because a player's own rating
raises the bar his side is expected to clear, so the index reads as a level and
a veteran and a newcomer are directly comparable. Its centre and spread are
pinned, dated constants measured once on one spine's population, never
re-centred per season — a career must be read with a ruler that does not change
length.
_Avoid_: score, normalised rating

**Worklist tier**:
How sure the per-player missing-match worklist is that a player belongs to a
*Held* match, on a three-rung ladder of shrinking confidence. **Certain** — the
player is named in the match's broken team sheet, which the gate read before
rejecting the match. **Appeared** — the match has no team sheet, but the vendor's
`appearances` record names exactly who played it, with minutes. **Maybe** — the
match has neither team sheet nor appearances, so no source states its lineups;
the player is only a candidate, drawn from the club's squad in the month around
the match date and ranked by how many nearby matches they actually turned out
for. A Maybe match is nonetheless rarely blank: its own events name whoever
scored, was booked or was substituted, which is most of a lineup but never all
of one (see *Derived lineup*). A worklist is per player and
is the input to the repair GUI (item 17); it holds candidates to *check*, never
rating decisions — a rating still moves only on a Clean or Released match (see
*Match state*, *Sidecar*).
A tier is a judgement about a *player*, so it says nothing on its own about a
match: a list scoped to a club rather than a player carries no tier and is read
by *Repair source* instead.
_Avoid_: confidence score — the tiers are ordinal rungs, not a number

**Derived lineup**:
A lineup nobody recorded, worked out from the match's own surviving records
instead of read off a team sheet. Two records can do it, in falling
completeness: the *appearances* record, which names everyone who played, and the
match's events, which name only whoever did something — scored, was booked, came
on or went off. Either way the split between the starting XI and the bench is
read from the substitutions: a named player who never came on started. A derived
lineup is a reading of the record and never a guess, so where the record is
silent it is simply short — the gap is closed by hand or the match stays *Held*.
_Avoid_: inferred lineup, guessed lineup, reconstructed lineup — nothing here is
estimated, and the derivation is not confined to matches being repaired

**Repair source**:
The surviving record a repair of a *Held* match starts from — what the match
still has to offer when it is opened — on a ladder of falling completeness.
**Team sheet** — one exists and is merely broken, so nearly a whole lineup is
already there. **Appearances** — no team sheet, but the appearances record names
everyone who played. **Events** — neither, so the lineup is a *Derived lineup*
read off whoever scored, was booked or was substituted. **Nothing** — no record
survives at all. A property of a *match*, and so the match-scoped counterpart of
*Worklist tier*: a tier says how sure we are that a player belongs to a match, a
source says what the match itself still has. A match whose source is Nothing can
be listed but not repaired — its events cannot be reconstructed, so releasing it
would assert that nothing happened in it.
_Avoid_: tier — a tier ranks players, a source ranks records

**Candidate rank**:
How likely the repair tool thinks a given player is the one being named, on a
three-rung ladder used to order the player picker: **rank 0** — he turned out
for this club within a month of this match, ordered by how many such nearby
matches he played; **rank 1** — he ever turned out for this club; **rank 2** —
everyone else. A typing aid and nothing more: it decides what order names are
offered in, never whether a name is right, which only a source outside the tool
can settle. Distinct from *Worklist tier*, the other ordinal ladder here — a
tier says how sure we are that a player belongs to a *match*, a rank says how
plausible a *player* is for a place being filled.
_Avoid_: confidence, likelihood — a rank is a display order, not a probability

**Manual player**:
A footballer the vendor does not name, whose name is therefore supplied by hand
and kept in the *Sidecar*. He arrives two ways, and the difference between them
is only whether the vendor happened to give him an id: **created** — no source
mentions him at all, so an id is minted for him from a reserved range that
cannot collide with the vendor's own; **named** — the vendor's records reference
his id but never spell out who he is, so he keeps that id and gains a name. He
is identified by his id and never by his name, so a name later corrected does
not make him a different man, and the hand-typed name is authoritative wherever
one is shown. He rates exactly like any other player: nothing in a replay
distinguishes him, because player identity reaches the engine through lineups
alone (see [ADR 0012](docs/adr/0012-manually-created-players.md)).
_Avoid_: fake player, synthetic player — he is a real footballer with a missing
record, not an invention
