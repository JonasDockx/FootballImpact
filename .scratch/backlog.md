# GoalImpact backlog

Future work, not yet scheduled. Captured during the design grill.

## 1. Store the date each match was played — DONE

Implemented: `Match` now carries a `LocalDate date` parsed from `match_date`;
`Main` sorts matches chronologically before processing (flat rule is
order-independent, so current results are unchanged — this is groundwork for
rule C).

**Why:** Global chronological order is the foundation of rule C, now designed as
an online career rating replayed in date order (see
[ADR 0005](../docs/adr/0005-online-career-rating.md)). Earlier results feed
forward into the expectation for later ones: beating a *stronger* side moves a
rating more than an expected result against a weaker one. Note the grill refined
two of the original intuitions here — there is **no recency decay** (ratings
accumulate instead), and the fixpoint solve was dropped in favour of a single
sequential pass, so chronological order matters for the *replay*, not for a
convergence loop.

**Data availability:** Good. The match date is already in the matches JSON
(`match_date`, e.g. `"2024-07-14"`). We can add it to the `Match` record and
populate it in `DataLoader.loadMatches` whenever we need it — a small, low-risk
change.

## 2. Store each player's date of birth

**Why:** Enables age-aware analysis — comparing a player against peers in the
same age band, and comparing age bands against each other (how does GoalImpact
develop with age?). The real Goalimpact makes age a first-class input: peak at
26 on average, with an *asymmetric* curve (rises faster than it declines), and
forecasts a young player's Peak-GI from DOB plus the population curve
(Übersteiger interview, see item 3). DOB is the gateway to all of that.

**Data availability — CAUTION:** StatsBomb Open Data lineup and event files do
**not** include player date of birth (they carry id, name, nickname, jersey,
country only). Realizing this item will require a *second* data source for DOB
(e.g. a supplementary dataset keyed by player name/id), which reopens the
data-source question we closed in
[ADR 0001](../docs/adr/0001-statsbomb-open-data-as-source.md). Treat sourcing DOB
as its own investigation before committing.

## 3. Time-integrated (clean-sheet-aware) residual source

**Why:** Rule C ships goals-only, so 0-0s and low-event matches carry no signal
and holding a strong side scoreless earns nothing. A time-integrated model would
accrue an expected goal-difference over each on-pitch segment from the strength
gap, so a clean sheet against a strong team becomes a positive residual. The rule
C design deliberately isolates the residual source behind a seam so this can slot
in without touching the update or rating machinery.

**Cost:** A bigger engine change (integrate an expected-goal rate over on-pitch
time rather than snapshotting at goal events) plus a base scoring-rate parameter
to calibrate.

**Confirmed direction (2026-07):** the real Goalimpact works exactly this way —
goal difference *per minute* versus expectation ("Plymouth's players receive
higher ratings even though they lost, because they lost by fewer goals than
anticipated"). Sources: [goalimpact.com/story](https://www.goalimpact.com/story),
[Goalimpact 1x1](https://goalimpact.substack.com/p/goalimpact-1x1). Committed to
pursuing this after global k/K tuning establishes a baseline.

**Read 2026-07-13 (user supplied the text):**
[Timbos kleine Taktikschule: Goalimpact](https://blog.uebersteiger.de/2018/03/19/timbos-kleine-taktikschule-heute-goalimpact/)
(Übersteiger interview with Goalimpact's Thorsten Wittmütz). Confirms the
expectation is a *fractional goal difference* per match ("wir arbeiten im
Nachkomma-Bereich"): a predicted rout ending only 1-0 credits the losing side's
players. Expectations are set per match *and per player*. Input data is exactly
ours: lineups, goals, subs, red cards — nothing else.

## 6. Adaptive per-player update factor (uncertainty-based K)

**Why:** With uniform K, a 47,000-minute veteran's rating swings as hard per
match as a debutant's — Messi losing ~2 rating points (20% of the dataset's top
rating) in one upset is noise, not signal. The real Goalimpact treats a rating
as meaningless before ~1,000 minutes and philosophically refuses to let one game
matter ("one game is insufficient"); the standard mechanism is a per-player
learning rate that shrinks with exposure (Kalman/TrueSkill-style uncertainty).

**Design note:** revisits the "start at 0, uniform K" cold-start decision from
the rule C grill — deliberately chosen for simplicity then, deliberately
superseded when this lands.

**Mini-grill done (2026-07-14), decisions in
[ADR 0006](../docs/adr/0006-exposure-based-update-factor.md):** deterministic
schedule `K(m) = K0·H/(H+m)` on career minutes, floor as a fraction of K0, no
inactivity widening (our patchwork data can't tell "injured" from "uncovered
league"), exposure frozen at kickoff, strength untouched, conservation given up
(glossary *Value* updated), schedule behind its own seam with explicit
uncertainty state as the named upgrade path. Ship gate: beat the best uniform-K
log-loss in one coarse (k, K0, H, floor-fraction) grid with the uniform
baseline embedded as floor-fraction = 100%. Ready to implement.

**Calibration anchors from the real Goalimpact** (Übersteiger interview, see
item 3): ~100 recorded games ≈ "relatively reliable" (their Pogba example),
~1,000 minutes before a rating is shown at all. And the Timo Schultz chart
shows the design goal explicitly: the headline rating ("Aktueller GI") is a
slow, smooth curve, while per-match volatility lives in a *separate*
"Über-/Unterperformance" series — noise is deliberately kept out of the rating.

## 7. Goalkeepers may need separate treatment

**Why:** The real Goalimpact rates goalkeepers "etwas anders" and excludes them
from field-player comparisons (Übersteiger interview). Our own first rule-C
leaderboard shows why: Prior (#11) and Bravo (#19) are keepers, floating high on
flat-shared team credit. Keepers play ~every minute, are never rotated, and
never contribute goals directly — their flat share of team performance has
different dynamics from outfield players. StatsBomb Starting XI events carry
position data, so tagging keepers is cheap once we decide what to do with them.

## 8. Normalized display scale

**Why:** Raw accumulated ratings (±10-ish) are meaningless to outsiders. The
real Goalimpact maps to a population-anchored scale with known reference
points: world class >160 (~200 players worldwide), Bundesliga average 135,
2. Bundesliga average 116–118, all-time top (Pogba, 2017) 200. Low priority —
presentation only, after the model itself stabilizes.

## 9. Cross-division calibration (rating islands within one gender pool)

**Why:** A league whose players only play each other is a zero-sum island: its
average rating is pinned at ~0 even if its true level is far below the top
division. Calibration flows only through **bridges** — cross-division cup ties,
promotion/relegation, player transfers, international duty. Each bridge match
moves zero-sum rating mass between pools; the deficit then redistributes through
the pool's internal matches. The real Goalimpact demonstrates this works (2.
Bundesliga avg 116–118 vs 1. Bundesliga 135) given enough bridge data.

**Rules of thumb when adding data:** (1) never add a division without its
bridges (cups and/or transfer flow) — a bridgeless league floats at a false
average; (2) cross-source player identity (item 5) is the transfer bridge's
prerequisite — without it, careers fragment and pools disconnect; (3) if bridge
convergence is too slow, consider seeding debutants at their competition's
current average instead of 0 — revisits the grill's cold-start decision, needs
its own mini-grill.

## 10. New data sources: API / scraper / manual-entry GUI

**Why:** StatsBomb Open Data is nearly exhausted as a source. Preference order
(user, 2026-07): (1) a football data API, (2) scraping, (3) a simple GUI for
manual entry of match events, plus master data (players, teams, competitions)
and transfer registration so entered lineups land on the right team.

**Design notes:** ADR 0004's loader boundary is the seam — every source (API,
scraper, GUI-backed store) must just produce `Match` + typed `MatchEvent`s. A
manual-entry GUI implies our own persistent match format, which would then also
be the natural target format for scraped/API data. The model itself derives team
affiliation from lineups, so transfer registration is entry-UX/validation, not a
model concept. Reopens ADR 0001; item 5 (player identity) is the hard part.

## 4. Checkpoint + incremental persistence

**Why:** Rule C recomputes ratings by replaying all history each run, which stays
cheap only while the dataset is modest. Once the historical dataset is frozen, a
final full replay can be checkpointed at a date `T`; later runs load the
checkpoint and replay only matches with `date > T`. Safe because the per-match
update is a pure function of `(pre-match ratings, match)` — but only append-only
after `T`; backfilling earlier matches invalidates the checkpoint and forces a
recompute. Do **not** build until full replay is actually too slow.

## 5. Cross-source player identity

**Why:** GoalImpact keys player identity on StatsBomb's global player ID. The
moment a non-StatsBomb source is added (StatsBomb Open Data is limited, so this is
expected), the same player will carry different IDs across sources and must be
reconciled, or careers will fragment. Its own investigation before any second
match/event source lands — related to the DOB sourcing question in item 2.
