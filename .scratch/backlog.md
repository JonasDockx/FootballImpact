# GoalImpact backlog

Future work, not yet scheduled. Captured during the design grill.

## 18. Transfermarkt loader, increment 1 — Premier League 2024/25

**Why:** [ADR 0009](../docs/adr/0009-transfermarkt-as-the-rating-spine.md) makes
Transfermarkt the spine. This is its first vertical slice: the whole path end to
end on one league-season small enough to inspect by hand, before it runs over
78,000 matches. **Ready to implement** — every decision below was settled in the
grill of 2026-07-21; what follows is the mechanical detail, so no re-probing is
needed.

### Inputs

- Vendor snapshot: `C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb`
  (~195 MiB, storage format version **64**, verified readable by DuckDB 1.5.4).
  Hardcode the path as a constant in `Main`, mirroring the StatsBomb `dataDir`.
- Dependency: `org.duckdb:duckdb_jdbc`, pinned. DuckDB reads older storage
  formats forward, so any current version opens this file; the risk is the
  vendor republishing on a *newer* format than the pinned jar.
- **First thing to write: a "count the games" smoke test** asserting 88,958 rows
  in `games`. A storage-format mismatch then fails on line one instead of three
  layers deep.

### Slice

`competition_id = 'GB1' AND season = '2024'` — 380 matches, 2024-08-16 to
2025-05-25. All 380 pass the gate; 1,115 goals (33 of them own goals), 3,207
substitutions, 1,610 cards of which **52 are match-ending** (26 `Red card`,
26 `Second yellow`), 106 same-minute goal/lineup-change collisions.

### Column-type traps (these bite on the first join)

The vendor's types are inconsistent across tables. Cast explicitly:

| Column | `games` | `game_events` | `game_lineups` | `appearances` |
|---|---|---|---|---|
| `game_id` | VARCHAR | VARCHAR | **INTEGER** | **INTEGER** |
| club id | INTEGER | INTEGER | INTEGER | INTEGER |
| `player_id` | — | INTEGER | INTEGER | INTEGER |

`clubs.club_id` is VARCHAR while every club id referencing it is INTEGER;
`competitions.competition_id` is VARCHAR. `games.game_id` is Transfermarkt's own
match-report id (`.../spielbericht/1026846`) and is stable across refreshes.

### Building the event stream

- **`StartingXI`** — `game_lineups WHERE type = 'starting_lineup'`, 11 rows per
  club. Goalkeeper is the row with `position = 'Goalkeeper'`. Player name comes
  from `game_lineups.player_name` (the `players` table is missing 69,943 players
  who appear in lineups).
- **`Substitution`** — `game_events WHERE type = 'Substitutions'`; `player_id` is
  the player going **off**, `player_in_id` the player coming **on**.
- **`Goal`** — `game_events WHERE type = 'Goals'`. **`club_id` is the beneficiary**,
  including on own goals (the scorer sits in the *other* club's lineup). That is
  the glossary *Goal* definition already satisfied — do not re-derive it from the
  crest or the description. `description LIKE '%Own-goal%'` identifies own goals;
  worth carrying for calibration but not needed for crediting.
- **`RedCard`** — `game_events WHERE type = 'Cards'` **and** the description's
  first comma-segment, stripped of a leading `"<n>. "`, is `Red card` or
  `Second yellow`. **Trap:** that leading number is a *season* counter, so
  `"3. Yellow card"` is a player's third booking of the season, not a second
  yellow in this match.
- **`MatchEnd`** — nominal: `(90, 0)`, or `(120, 0)` where extra time is
  detected. Detect extra time from `appearances.minutes_played = 120`, not from
  event minutes: a quiet extra time leaves no event trace.

### Clock and ordering

- `t = (minute − 1) · 60 + 30` — the midpoint of the labelled minute. No event in
  the whole database carries `minute = 0`, so `t` is never negative.
- `period` from the minute: ≤45 → 1, ≤90 → 2, ≤105 → 3, else 4.
- Every event of a minute gets the **same** `t`. Order lives in the list.
- Within a minute, sort **red card → goal → substitution**, with one exception:
  if a goal's scorer is the `player_in_id` of a same-minute substitution, that
  substitution sorts *before* the goal. Write it as a named comparator with a
  test, not as a SQL `ORDER BY`.
- Drop events with `minute = −1` and `type = 'Shootout'` rows outright.

### Home side

All 380 are league matches with rounds named `"N. Matchday"`, so the label is
trusted and **every match is `HOME`**. Correct, but note what it means: the
slice does **not** exercise `AWAY`, `NEITHER`, the finals rule, the tournament
host rule, extra time, or the skip path. Pick increment 2 to cover them — a
domestic cup season plus a finals tournament would hit all of them at once.

### Gate

1. The StatsBomb path still yields log-loss **0.6259** with pinned knobs. The
   new loader must not disturb the old one.
2. Every match either replays or is counted as skipped **with a reason**. All
   380 should replay; anything else is a bug in the gate, not in the data.
3. The leaderboard is recognisable by eye. A broken clock, comparator or venue
   rule shows up here in seconds and in no test.

Run on the existing base scoring rate (0.01473) and knobs (`K0 = 1.0`,
`H = 4,000`, `h = 2.5`) **knowingly wrong** — they were measured on a
Barcelona-heavy StatsBomb slice. Increment 1 proves the pipeline, not the model.
Re-measurement waits for the full ingest (ADR 0009, Consequences).

### Deliberately not built in this increment

No interface extracted from `DataLoader` — a second independent class, on item
11's precedent that a seam needs a second consumer before it earns its keep.
The sidecar file is **not created**; its first row should be a repair that was
actually needed. No issues table, no `source` column, no `rating_eligible` flag,
no GUI (item 17), no rating history (that arrives with the full ingest).

### Outcome (2026-07-21) — DONE, all three gates met

Landed in five stages, each with its own gate: (A) `duckdb_jdbc` 1.5.4.0 pinned +
`SnapshotSmokeTest` (88,958 games); (B) `TransfermarktLoader.loadMatches` and the
home-side rule; (C) `EventOrdering`; (D) `loadEvents` — lineups and the usability
gate first, then goals/subs/red cards; (E) the `Spine` switch in `Main`.

**Gate 1** — StatsBomb re-run after the `Main` refactor: log-loss **0.6259**, base
rate 0.01473, anchor 2.69. Byte-identical to the champion; the old path is
undisturbed. **Gate 2** — `GB1 2024: 380 of 380 matches replay, 0 events dropped`.
**Gate 3** — the leaderboard is Liverpool's title-winning side (Díaz,
Alexander-Arnold, Mac Allister, Robertson, Gravenberch, Van Dijk, Salah) with City
and Arsenal behind it. 91 tests green.

Numbers this slice produced, all expected and none yet actionable:

- **Base scoring rate 0.01630** goals per team-minute (1,115 goals / 68,400
  team-minutes — exactly 380 × 180, the nominal clock with no stoppage in the
  denominator). Higher than StatsBomb's 0.01473 for both reasons at once.
- **Home share 51.6%**, an `h` anchor of **0.63** rating points against the pinned
  `h = 2.5` — this run applies roughly four times the home advantage the
  population shows. Whether that is a 2024/25 England fact or a spine-wide one is
  a question for the full ingest's re-measurement.
- **Log-loss 0.6685**, not comparable to 0.6259: one season means every rating
  starts at 0 and burns in across the very matches being scored. The `Ship gate`
  and `Item 11 gate` lines `Main` prints are pinned to the StatsBomb population
  and are meaningless on a Transfermarkt run.

Three design points settled during implementation, worth keeping:

- **The same-minute rule is a rank, not a comparator.** This item's text said
  "named comparator"; a pairwise comparator is *intransitive* here — a second goal
  in the minute sorts before the substitution that must sort before the first goal
  — and Java's sort throws on it. `EventOrdering` assigns each event a rank
  (red → scoring substitute → goal → substitution) and sorts by it. Test
  `theExceptionSurvivesASecondGoalInTheSameMinute` pins the arrangement.
- **`UnusableMatchException` is checked**, because dropping matches is normal
  rather than exceptional here, and exposure drives the update factor — a silently
  dropped match manufactures false debutants.
- **Both lineup types are read**, not just `starting_lineup`: `game_events`
  carries ids but no names, so an arriving substitute is nameable only from his
  bench row. The Goalkeeper tag is still taken from starters only, per the
  glossary.

Verified against the file while implementing: no event in the database carries
`minute = 0` (all 17,575 non-positive stamps are `−1`), and the maximum minute
anywhere is 120.

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

## 15. Pre-2013 depth: earlier seasons and international qualifiers

**Why (user, 2026-07-21, emphatic):** the Transfermarkt snapshot has no lineup
before 2013, so no career curve can honestly begin earlier — Iniesta's line
starts at 29. "I am not content in just accepting that 2013 is the start of our
current datasource and that this cannot be helped." Ingesting 2008–2013 must
**recompute the whole career**, which is why full replay stays the only mode
(item 4 must stay unbuilt).

Equally wanted, and larger: **international qualifiers**. The snapshot's five
national-team competitions are finals tournaments only — 742 games. World Cup
and Euro qualifying plus Nations League is roughly ten times that for UEFA
alone. Qualifiers matter out of proportion to their count because they are among
the very few competitive fixtures where players from otherwise disconnected
leagues meet — the bridges of item 9. Friendlies are absent too, and are *not*
wanted on the same terms (six substitutions and half-time rewrites break the
assumption that a stint reflects a competitive coaching judgment).

**Route (ADR 0009):** prefer running the vendor's own scraper + dbt pipeline
with a wider season range over writing a Java scraper — the output shape stays
identical, so the read path never learns where the rows came from. A hand-rolled
parser means reimplementing the vendor's normalisation and keeping it in sync
forever. Same project also covers **fidelity**: stoppage minutes and per-goal
running scores, both destroyed by the vendor's pipeline and both unfixable by
any refresh.

**Caveats:** Transfermarkt's robots.txt disallows bots — personal use, slow
rate, cache everything, and a multi-year backfill is a once-off batch left
running for days. Very old match sheets differ in markup; spot-check per era.

## 16. A prior for unrated players (the cup-minnow inflation)

**Why (measured 2026-07-21):** 2,501 of the 3,274 clubs in the Transfermarkt
snapshot never play a single league match in it — they exist only as cup
opposition, and account for **512,380 lineup rows, 16% of all
player-appearances**. A fourth-tier side in the DFB-Pokal is rated 0, which in
this model means *exactly average*, not *unknown*. So the strength gap at
kickoff is zero, expected GD over ninety minutes is zero, and the Bundesliga
eleven collect a full +4 of unearned residual for beating a team the model
believed was their equal. The minnows do sink afterwards (ADR 0006 gives them
the largest update factor) but they often never play again, so the correction
arrives too late to protect anyone and is never reused.

**This is a ranking bias, not a level shift:** a player whose club reaches the
final beats five or six unrated sides; one knocked out in round one beats none.

**Direction:** this is item 9's rule-of-thumb (3) made concrete — seed debutants
somewhere other than the population mean, e.g. at their competition's current
average. Needs its own mini-grill; it is a change to the *metric*, not to
ingestion. Accepted for now (ADR 0009) with a **diagnostic built alongside the
first ingest**: report per player how much residual came from opposition with no
league football in the data, so "is this a problem?" becomes a number.

## 17. Match repair / manual entry GUI

**Why (user, 2026-07-21):** ADR 0009 makes corrections **match-level
replacements** — materialise a match into the sidecar, pre-filled from the
vendor where possible, edit it, and your version wins outright. That collapses
repairing a broken 2019 Premier League match and authoring a 1998 Belgian
third-division match into *one operation*, so it is also one screen. ~11,000
matches fail the ADR 0009 gate; cleaning even a fraction by hand needs a tool.

**Design notes carried from the handover:** keyboard-first entry — header, two
lineup columns, events timeline; a **ranked player picker** (rank 0 = has a
spell at the selected club near the match date, rank 1 = ever linked, rank 2 =
everyone else, rows showing DOB and last-seen club to split identical names,
bottom row always "create '(typed name)'"); **roster prefill** from the club's
most recent XI, which makes a chronologically-entered season a handful of edits
per matchday; live validation as red field highlights. Manually created entities
take IDs ≥ 1,000,000,000, a reserved range that cannot collide with real
Transfermarkt IDs. This is also when the three-Maven-module split (declined in
ADR 0009) earns its keep — JavaFX is the heavy dependency worth isolating, not
`duckdb_jdbc`.

**Prerequisite:** the sidecar has actual content, i.e. a repair you needed. Do
not build the tool before the first real repair tells you what it must hold.

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

**Largely resolved by [ADR 0009](../docs/adr/0009-transfermarkt-as-the-rating-spine.md)
(2026-07-21):** the Transfermarkt spine carries `date_of_birth`, and **2.86M of
3.18M lineup rows (90%) resolve to one**. The residual gap is a long tail —
69,943 players appear in lineups with no row in the `players` table at all
(mostly cup appearances by clubs the vendor doesn't track). They still rate and
still act as opponents; they simply cannot be plotted against age. The
asymmetric-peak analysis this item exists for is now unblocked.

## 3. Time-integrated (clean-sheet-aware) residual source — DONE

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

**Grill done (2026-07-15), decisions in
[ADR 0007](../docs/adr/0007-time-integrated-residual.md):** two per-team
scoring rates — a *measured* base scoring rate (goals ÷ team-minutes, never a
grid dimension: the who-scored log-loss is blind to it; **re-measure it when
large new eras/competitions land**) bent multiplicatively by the strength gap,
recovering the logistic link as the who-scores half. Residual = full `±1`
scoreboard jumps minus a continuously draining expected GD, per player over
their stints; per-goal surprise (`1 − P`) is retired. The loader owns a new
continuous playing clock + `MatchEnd` event (fixes the exposure undercount and
the backwards-jumping half-time clock); the seam becomes `ResidualSource`
(`goal` + `segment`), `MatchProcessor` chops segments, old rules stay
expressible via an empty `segment` default. Deferred with backlog items: score
effects (12), man-count-aware rates (13). Staged landing: clock fix +
re-baseline → measure base rate → pure seam refactor → new model + (K0, H)
re-grid. Gate: parity-or-better log-loss vs the re-baselined old rule, plus
named-match clean-sheet demonstrations. Ready to implement.

**Stage 1 landed (2026-07-15):** continuous playing clock + `MatchEnd`
shipped; re-baselined log-loss **0.6331** (unchanged at four decimals — the
number stage 4 must beat or match). The 2,651-match replay surfaced three
boundary cases, each pinned in the loader: post-whistle cards clamp to the
end of play (Valdés, Clásico 70272); a period ends at its latest in-play
stamp, not the Half End stamp (Puyol at 93:07 vs whistle 93:06, match 68339);
and short-format halves (2×40 youth matches) no longer inherit phantom
minutes from the nominal 45:00 second-half start. Old-vs-new CSV audit: all
34 minute decreases accounted for (3 duplicate-name join artifacts — all
actually gained; 31 short-half corrections); total minutes +10.1%, largest
gain Messi +4,510. A whistle-monotonicity tripwire (whistle ≥ every event
stamp) guards the clock invariant.

**Stage 2 done (2026-07-15): base scoring rate measured = 0.01473 goals per
team-minute** (7,496 goals / 509,022 team-minutes on the honest clock; ≈ 2.83
goals per match over 96.0 played minutes on average — old eras and the
Barcelona-heavy La Liga slice run high). This is the pinned calibration
constant for stage 4; re-measure when large new eras/competitions land.

**Stage 3 done (2026-07-15):** seam refactored to `ResidualSource` (`goal` +
default no-op `segment`); `MatchProcessor` chops lineup-constant segments
(subs, red cards, whistle — goals deliberately don't chop) and merges segment
deltas into the same per-match accumulation; chopping pinned by a
recorder-fake test. Gate held: output byte-identical (CSV cmp clean,
log-loss 0.6331), all 40 tests green.

**Stage 4 shipped (2026-07-15):** `TimeIntegratedResidual` behind the seam;
grid re-tuned to K0 = 1.0, H = 4,000 (interior on both axes; K0 halved as
predicted — every match now signals). Gate passed on both halves: log-loss
**0.6326 vs 0.6331** goals-only baseline, and the named-match demo shows both
directions — England 0-0 Slovenia: all Slovenia +, all England −, scaled by
per-player K and stint length; Georgia 2-0 Portugal: ±2.5 swings, Ronaldo
docked least. Leaderboard shift: defenders rise (Puyol #6). Outcome details
in ADR 0007.

## 12. Score-aware scoring rates (the drip has no memory)

**Why:** The time-integrated residual (item 3) drains expectation at a rate set
only by the strength gap, indifferent to the current score. Real teams manage
the score: a side 3-0 up eases off, a trailing side chases. So expected GD is
slightly mis-calibrated in lopsided matches — the model expects a leading team
to keep scoring at full rate. Accepted during the item-3 grill (2026-07-15) as
a deliberate simplification: the real Goalimpact also works from match-level GD
expectations. Revisit only if blowout matches visibly distort ratings — a fix
would condition the two rates on the current scoreline, which needs its own
calibration data.

**Prerequisite:** item 3 shipped.

## 13. Man-count-aware scoring rates (red cards)

**Why:** Item 3's drip rates are computed from count-invariant Strength (an
average), so a side reduced to 10 is expected to perform as if still 11 — for
every remaining minute. The ten survivors systematically absorb negative
residual for a structural disadvantage (roughly half a goal per 25 minutes by
published estimates), while the sent-off player stops accruing at the card.
Accepted during the item-3 grill (2026-07-15): rare, bounded, and not a new
wrongness — glossary *Strength* documents the count-invariance, and the
goals-only rule had the same blindness at goal instants.

**Design note:** the `ResidualSource.segment(teamA, teamB, seconds, ratings)`
seam already exposes the man count (the set sizes), so the fix is internal to
the rate function — no engine or interface change. The real work is
calibration: our data has too few red cards to measure the per-man effect
well, and importing a literature number is its own mini-decision.

**Prerequisite:** item 3 shipped.

## 14. Home-field advantage in the expectation

**Why (user, 2026-07-15):** home advantage is one of the largest documented
effects in football (roughly 0.3–0.5 goals of expected GD per match), and the
time-integrated model (item 3) currently expects equal teams to draw
*anywhere*. Home sides therefore systematically beat expectation and away
sides under-shoot it. It roughly washes out per player over a career (players
play ~half home, ~half away) but it is unmodeled signal in the who-scores
probability and the drain — likely the largest known systematic error now
that item 3 has shipped.

**Design notes:** enters naturally as a constant added to the home side's
effective strength gap (equivalently, a rate multiplier), calibratable from
the data like the base scoring rate. Two real problems to grill before
building: (1) *plumbing* — `Match` knows home/away but `MatchProcessor` and
the `ResidualSource` seam see only events and lineups, so venue must travel
into the rule; (2) *neutral venues* — StatsBomb designates a "home team" even
for neutral-site tournament matches (World Cups, Euros), where the label is
administrative, not an advantage (except hosts) — applying HFA naively there
injects noise. Needs its own mini-grill.

**Grill done (2026-07-15), decisions in
[ADR 0008](../docs/adr/0008-home-advantage-in-the-expectation.md):** one
constant `h` in rating units shifts the home side's effective gap everywhere
(who-scores and drain alike); ratings stay venue-neutral. Who is at home is a
loader-owned conclusion (home team / away team / nobody) under a **two-world
rule**: domestic → trust the label except single-match finals and the ISL
2021/22 bubble season (115 matches, 3 stadiums — all labels fiction, found
during the grill); cross-border → home iff team country == stadium country
(pays Euro 2020's 27 own-country matches and mislabeled host Germany 2024,
leaves Qatar 2022 neutral). Plumbing: conclusion on `Match`, `boolean home`
on `StartingXI` (at most one, tripwired), seam deepened to
`Lineup(players, home)` — the named landing spot for items 11 and 13.
Calibration: h joins the grid (it does NOT cancel from the log-loss, unlike
the base rate) with h = 0 as the embedded baseline plus a printed
home-goal-share sanity anchor. ~90 COVID ghost matches accepted as documented
calibration noise. Staged landing: (1) inert plumbing at h = 0,
byte-identical gate; (2) grid on, gate **strictly** beat 0.6326 + anchor
vicinity + named demos.

**Redesigned mid-implementation (2026-07-16):** reviewing the flagged
matches exposed the country test as a *host-nation* test misapplied to
clubs — it made Man United "home" at the 2011 Wembley final and Ajax "home"
at De Kuip 1972, while degrading Napoli's genuine 1989 home leg vs Juventus
to neutral. Final rule forks on `competition_international`: national teams
→ geography (country == stadium country); clubs → trust the fixture label,
minus single-match finals, minus **curated source facts** (`NEUTRAL_SEASONS`:
ISL 2021/22 bubble; `HOME_SIDE_OVERRIDES`: Napoli's two-legged-final home
leg). Bayern's 2012 "Finale dahoam" deliberately left neutral. Bonus: the
old same-country limitation vanished — labels handle future all-Spanish
European ties; no club→stadium map ever needed.

**Shipped (2026-07-16):** h = **2.5** (grid winner, interior on 0–4;
K0/H reconfirmed in a 27-cell cross-check), log-loss **0.6259 vs 0.6326**
venue-blind baseline — the largest single tuning gain yet (0.0067; adaptive
K was 0.0004, time-integration 0.0005). Anchor agreed: home sides scored
56.7% of goals → h ≈ 2.69. Implied venue edge between equals ≈ 0.35 goals
of expected GD per match, inside the 0.3–0.5 this item predicted. Demos:
Müller −0.19 for a goalless cameo in a 5-1 *home* win, late Scotland subs
positive, England 0-0 Slovenia (neutral) reproduces ADR 0007's demo, Real's
home 0-4 collapse docked −2.6..−3.6. Leaderboard: Pepe to #2, Zlatan/Cavani
down. Outcome details in ADR 0008.

## 6. Adaptive per-player update factor (uncertainty-based K) — DONE

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

**Implemented and tuned (2026-07-14):** `SmoothFadeSchedule` behind the
`UpdateSchedule` seam; `MatchProcessor` sizes updates from exposure frozen at
kickoff (the substitution trap is pinned by a test). Tuning found that only the
*product* k·K0 matters (predictions see only k·gap), so k is pinned at 0.10 and
K0 carries the tuning; the winning cell is K0=2.0, H=4,000 min, interior on
both axes. Ship gate passed: log-loss 0.6331 vs 0.6335 best-uniform, and the
cosmetic win is large (veterans ~6× steadier relative to scale, debutants
learning ~8× faster). Caveats: the floor never binds within observed careers
(fade reaches it only past ~196,000 min), so the late-career-decline worry is
*untested*, not resolved; and keepers (item 7) are now the most visible
distortion — Bravo #3 and Valdés #4 on the adaptive leaderboard. Details in
[ADR 0006](../docs/adr/0006-exposure-based-update-factor.md).

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

**Mini-grill done (2026-07-15), display-only resolution — no ADR (trivially
reversible, no real trade-off surrendered):** Diagnosis committed:
**collinearity, not keeper-ness** — the model identifies an individual only
through lineup variation, so a never-rotated player's rating is a
team-performance proxy wearing a player's name; keepers are merely the
*systematic* case (an ever-present centre-back would distort identically).
Evidence that closed the "just an exposure story?" branch: adaptive K made
keepers *more* prominent (Bravo #3, Valdés #4), so no K/exposure tuning can
ever fix this. Consequences: the **engine is untouched** — keepers keep full
credit/blame and stay in Strength (their team-proxy rating is real predictive
signal; glossary *Strength* unchanged). The fix is comparison-scoping only:
new glossary term **Goalkeeper** (ever started at GK — career-level, sticky;
emergency keepers stay field players); `StartingXI` gains a `Player goalkeeper`
field (lineup position id 1, loader fails loudly if absent) inside the
ADR 0004 boundary — position must NOT go on `Player`, whose record equality
drives on-pitch set removal; `MatchProcessor` folds it into a sticky flag on
`PlayerTally`; the printed leaderboard becomes field-players-only and the CSV
keeps everyone plus a goalkeeper column. **No keeper board is printed**: our
keeper ratings compare teams, not keepers, so a keeper-vs-keeper ranking would
be dishonest until keepers are rated differently (the real GI's "etwas
anders", explicitly not attempted here). Ship gate: **identity + cosmetic** —
log-loss unchanged at 0.6331 with pinned knobs, printed top-20 keeper-free,
Valdés/Bravo flagged in the CSV, tag semantics pinned by a unit test (set at
GK start, survives later outfield matches, emergency keeper never earns it).
Declined: gated field-players-only Strength experiment, zero/reduced keeper
credit share, per-match keeper tracking, two printed boards.

## 11. Field-players-only Strength experiment

**Why:** Item 7's grill committed to the collinearity diagnosis and
deliberately left keepers in Strength: their team-proxy rating is *presumed*
to be genuine predictive signal. That presumption is untested. The experiment:
compute lineup strength as the average of on-pitch **field players** only,
behind the existing `strength()` seam, judged by the log-loss harness. This
changes the prediction path, so the full gate applies — adopt only if it beats
the current best (0.6259 since ADR 0008; the 0.6331 first written here
predated home advantage); either way the result quantifies how much of a keeper's
rating is informative team signal. If adopted, the glossary *Strength* entry
changes to say "field players".

**Design note before typing:** the credit path sees `Set<Player>` and a
`RatingLookup`, but the goalkeeper tag (item 7) lives on `PlayerTally` — the
flag needs plumbing into the rule (e.g. a keeper-aware lookup, or carrying the
tag alongside the player). Decide that seam shape first; it is the only real
work in the experiment.

**Grill done (2026-07-16), no ADR (reversible; item 7 precedent):** the
"existing `strength()` seam" wording above was loose — `strength()` is a
private helper of `TimeIntegratedResidual`, not a seam. The variant enters as
a constructor flag on the rule, exposed in `Main` as a `{false, true}` grid
dimension with the status quo embedded as the baseline cell (the floor = 1.0
/ h = 0 pattern; a `StrengthFunction` seam was declined — no second consumer
on the board — and a duplicate rule class rejected outright). Filter fact:
the career *Goalkeeper* tag read **live** from tallies — stamped during
StartingXI processing, so today's starter (even a career debutant) is always
covered, and a subbed-on previously-tagged keeper too. Accepted as noise: a
debut backup keeper subbed on (untagged that match, counts as field player)
and a sticky-tagged ex-keeper playing outfield (excluded anyway; essentially
never occurs). Plumbing lands on ADR 0008's named spot:
`Lineup(players, home, Set<Player> goalkeepers)` — the subset of on-pitch
tagged players; empty set = no keeper on pitch, so a keeper red card just
works; `players` stays the full lineup and credit/blame is untouched (item
7's commitment). The filter applies to `goal()` and `segment()` alike — one
model, never split (ADR 0008 precedent). Gate **best-vs-best, strict**: the
variant cells get their own h sweep (0–4) plus a K0/H cross-check, and the
variant's best must strictly beat **0.6259** at four decimals — a tie keeps
the status quo. Staged landing: (1) inert plumbing, gate byte-identical CSV
+ 0.6259 unchanged, processor tests pin the subset semantics (starter in;
tagged sub in; untagged debut sub out; sent-off keeper out); (2) grid flag
on, rule test (keepers out of the average, still in the deltas,
empty-after-filter hits the existing empty-set guard), run the gate. Either
outcome is the deliverable — a loss quantifies what a keeper's team-proxy
rating is worth in log-loss. Glossary *Strength* changes to "field players"
only on adoption. Ready to implement.

**Stage 1 landed (2026-07-16):** `Lineup(players, home, goalkeepers)` — the
on-pitch subset carrying the career tag, read live from the tallies at both
construction sites; no rule reads it yet. Gate held: byte-identical CSV,
log-loss 0.6259 unchanged, 66 tests green (3 new pin the subset semantics:
debut starter in, tagged sub in, untagged debut sub out, sent-off keeper
out).

**Stage 2 run — REJECTED (2026-07-16). Goalkeepers stay in Strength.** The
variant lost at every h in the sweep (9 of 9, by a steady 0.0001–0.0002),
and the 81-cell cross-check (K0 ∈ {0.5, 1, 2} × H ∈ {2000, 4000, 8000} ×
h ∈ 0–4) settled the one loophole worth checking — that filtering rescales
the gaps and might want different knobs. It doesn't: the variant's best cell
is **the champion's own knobs** (K0 = 1.0, H = 4,000, h = 2.5), interior on
all three axes, at **0.6261 vs 0.6259**. No knob combination rescues it.

**What this bought:** item 7's presumption — that a keeper's team-proxy
rating is genuine predictive signal in Strength — is now **tested, not
assumed**. The signal is worth **0.0002** of log-loss: small, but the same
order as the gains that shipped (time-integration 0.0005, adaptive K
0.0004; venue 0.0067). Not noise, and not free to discard. The filter is not
cosmetic either — the variant's leaderboard moves real distances (Rakitić
4→9, Puyol 5→4) — it just moves them the wrong way. Glossary *Strength*
therefore unchanged, now on evidence rather than presumption.

**What stayed in the code:** the `fieldPlayersOnly` flag on
`TimeIntegratedResidual` and `FIELD_PLAYERS_ONLY = {false}` in `Main`,
deliberately kept as executable documentation — flipping it to `{true}`
re-runs the experiment, so the record can never drift from the code. The
`Lineup.goalkeepers` subset likewise stays: it is stage 1's plumbing, still
inert in the shipped model, and the natural input for any future
keepers-rated-differently work (the real GI's "etwas anders", still not
attempted).

**Not tested by this item:** whether keepers should be *rated* differently —
only whether they belong in the strength average. The collinearity
diagnosis from item 7 stands untouched.

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

**Superseded (2026-07-21) by
[ADR 0009](../docs/adr/0009-transfermarkt-as-the-rating-spine.md)**, which
settles the source question (Transfermarkt spine; StatsBomb demoted to fixtures
and calibration), and by items **15** (scraping — for depth and fidelity, not
freshness) and **17** (manual entry, now unified with match repair). The
preference order recorded here was overtaken by events: a curated public dataset
covering 65 competitions beat all three options.

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

**Deferred, not resolved, by
[ADR 0009](../docs/adr/0009-transfermarkt-as-the-rating-spine.md) (2026-07-21):**
the *Spine* rule sidesteps this entirely — exactly one source feeds a rating
run, so there is no second ID space to reconcile. Transfermarkt's `player_id`
*is* the profile-URL id, shared with the vendor's scraper, so the depth project
(item 15) needs no matching either. This item goes live only if a genuinely
different provider is ever pooled — at which point the handover's sketch
applies: canonical IDs in base tables, native IDs in the raw layer, never
overwritten in place; matching on DOB + normalised name, with club + shirt
number settling almost everyone inside a fixture; leftovers to manual review.
A `player_alias(old_id → canonical_id)` table is worth having sooner regardless,
for Transfermarkt's own profile merges and redirects.
