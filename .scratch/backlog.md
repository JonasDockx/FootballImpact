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

## 19. Transfermarkt loader, increment 2 — cup, tournament, venue forks — DONE

**Why:** item 18 landed the spine on a slice that was clean in a way that hid
things: 380 league fixtures, every one `HOME`, none skipped, none past 90
minutes. Six paths therefore shipped unexercised — `AWAY`, `NEITHER`, the club
finals rule, the tournament-host rule, the 120-minute path and the
skip-and-count path. This increment picks a slice that hits all six.

**Data availability, probed 2026-07-21:** only **four finals-tournament editions
in the whole snapshot are replayable** — Asian Cup 2024 (44 of 51 with lineups),
AFCON 2025, Copa América 2024, World Cup 2026. Euro 2024 and every World Cup
before 2026 have **zero lineups**, so ADR 0009's "~40 curated host rows" is
really four.

**Grill done (2026-07-21), six decisions, no new ADR** — every answer is ADR 0009
as written except two amendments recorded below:

1. **Slice: FA Cup 2024/25 (123) + Asian Cup 2024 (51)**, replayed with the PL
   season as one pooled run in date order.
2. **Extra time on either signal** — an event past minute 90 **or**
   `appearances >= 120`. This **amends ADR 0009**, which pinned `appearances`
   alone on the reasoning that a quiet extra time leaves no event trace: true of
   28 matches, while `appearances` is missing entirely for **1,174** that plainly
   played extra time. In the FA Cup slice it sees 32 extra-time ties where
   `appearances` sees 16 — exactly half.
3. **The host rule beats the finals rule** inside a national-team competition.
   The 2024 Asian Cup final was Jordan-labelled-home vs Qatar-labelled-away at
   Lusail: Qatar's genuine home match, so `AWAY`. The club finals rule never runs
   in a tournament.
4. **The host table stays a compiled constant** — three curated rows, growing
   about one a year. This **defers ADR 0009's sidecar**, whose own text says its
   first row should be a repair that was actually needed. Revisit when a real
   repair arrives or the table outgrows a `Map`.
5. **FA Cup semi-finals are curated neutral**, scoped as `(competition, round)`.
   Both 2024/25 semis were at Wembley. A blanket rule on the round name would be
   wrong in eleven other competitions to be right in one.
6. **The post-whistle tripwire skips and counts**, rather than killing the run:
   ADR 0009 already rejected hard gates on a spine of 78,000 vendor-supplied
   matches.

**Stages landed (2026-07-22).** F1 the three-way home-side rule on a `Fixture`
record; F2 extra time + tripwire; F3 `Main` replays a list of slices as one pool;
F4 the home-goal anchor counted from goal events.

**Result — all gates held.** 100 tests green.

| Gate | Result |
|---|---|
| StatsBomb unchanged | log-loss **0.6259**, anchor still 2.69 |
| Increment 1 unchanged | GB1 alone: **380/380, 0.6685**, identical leaderboard |
| New slice | **547 of 554 replay**, 7 × `no lineups`, 0 events dropped |
| Venue forks | **HOME 504, AWAY 3, NEITHER 40** — every fork exercised |
| Leaderboard | still recognisable with three competitions pooled |

Pooled run: base rate 0.01594, home share 51.9%, anchor h ≈ 0.76, log-loss
0.6761 (vs 0.6685 for the league alone — cup ties bring unrated minnows, which
is item 16's known bias, and 2,924 players instead of 562, so more of the run is
burn-in).

**Defect found and fixed in F4: the home-goal anchor was counting shootout
penalties.** Transfermarkt folds shootout results into `home_club_goals`, so the
scoreline says 510 goals across the FA Cup slice where the events say 375. The
residual model never saw them — it reads goal events only — but the anchor that
ADR 0009 says must drive the `h` re-measurement did. Now counted from events on
both spines; the StatsBomb anchor is unmoved at 2.69, confirming its scorelines
and events already agreed.

**Still not built:** the sidecar; per-match home-side overrides; multi-host
per-match rows (World Cup 2026 is absent from the host table, so its matches are
all neutral); rating history; any interface extracted from `DataLoader`.

## 20. Transfermarkt loader, increment 3 — the full ingest — DONE

**Why:** increments 1 and 2 (items 18, 19) proved the whole path on 547 matches
and left **no untested fork in the loader**. Everything ADR 0009 lists under
Consequences now waits on one thing: running the whole snapshot. The three
calibration constants the model rests on — the base scoring rate, `h`, and the
`(K0, H)` grid — are all still pinned to a Barcelona-heavy StatsBomb population
and cannot honestly be re-measured on anything smaller than the full spine.
**Ready to grill** — everything below was probed 2026-07-22, so no re-probing is
needed.

**Reading order for a fresh context:** CLAUDE.md → CONTEXT.md → ADR 0009 (note
its 2026-07-22 amendment) → backlog items 18 and 19 → this item.

### What is already built and must not regress

`TransfermarktLoader` (matches, lineups, goals, substitutions, red cards,
nominal whistle, three-way home side, curated host table, curated neutral
rounds, post-whistle tripwire), `EventOrdering`, `UnusableMatchException`, and
`Main`'s `Spine` switch with a `SLICES` list. 100 tests green. Pinned numbers:

| Run | Must stay |
|---|---|
| StatsBomb spine | log-loss **0.6259**, anchor 2.69 |
| `SLICES = [GB1/2024]` | **380 of 380**, log-loss **0.6685** |
| `SLICES = [GB1, FAC, AFAC / 2024]` | **547 of 554**, 7 skips, HOME 504 / AWAY 3 / NEITHER 40 |

### Size and cost, measured 2026-07-22

- **88,958 games, 70 competition ids, 18 seasons.** ADR 0009's strict gate
  leaves ~77,781 usable.
- **1,256,894 event rows** survive the loader's filters (247,685 goals, 631,240
  substitutions, 377,969 cards before the sending-off test), plus **1,780,759
  starting-lineup rows**.
- **Query cost is not the problem.** 380 matches through today's
  three-queries-per-match path take 0.85 s — **~3 minutes extrapolated to all
  88,958**. The same rows fetched batched per competition-season take 0.03 s, a
  **26× speedup** (~8 seconds full ingest). Batching is an optimisation to
  decide on, not a prerequisite.
- **Memory is the open sizing question.** `Main` holds every replay in RAM so
  each grid cell can replay from memory: order 1.2M `MatchEvent` objects and
  ~1.7M `Player` instances (11 per XI, re-created per match, never
  deduplicated). Unmeasured. The 81-cell grid multiplies replay *time*, not
  memory.

### Lineup coverage by competition type (the gate's shape)

| Type | Games | With lineups |
|---|---|---|
| domestic_league | 63,382 | 58,366 |
| domestic_cup | 12,904 | 11,805 |
| other | 7,599 | 6,819 |
| international_cup | 3,117 | 2,901 |
| (null) | 1,214 | 1,106 |
| national_team_competition | 742 | 200 |

`competition_type` is **null for 1,214 games**, which `classifyHomeSide` treats
as club football today — untested, and possibly wrong.

### Questions the grill has to settle

1. **Which competitions enter the run?** All 70 ids, or a filter. ADR 0009
   deferred a `rating_eligible` flag because every competition is competitive
   senior football — worth re-testing against the 1,214 null-type games and the
   7,599 in the `other` bucket.
2. **Batch or not**, given that 3 minutes is already tolerable and batching
   changes the loader's shape.
3. **How the grid runs at scale** — 81 cells over ~78,000 matches, and whether
   all replays stay in memory.
4. **In what order the constants are re-measured.** The base rate, `h` and
   `(K0, H)` are not independent: the `h` anchor is expressed in rating points
   through the link gain.
5. **The `h` anomaly.** Both Transfermarkt slices measure 0.63–0.76 rating
   points against the pinned 2.5, and a 51.9% home-goal share against
   StatsBomb's 56.7%. Either modern club football has far less home advantage
   than the StatsBomb-era mix, or something in the venue handling is wrong. The
   full ingest is the instrument that separates those.
6. **Left-censoring and burn-in** (glossary *Left-censored career*): lineups
   start in 2013, so every career open in 2013 is censored and 2013–2014 is
   burn-in for the whole run. ADR 0009 rejected a warm-up pass; the diagnostic
   that reports the effect is unbuilt.
7. **Unrated opponents** (item 16): 2,501 of 3,274 clubs never play a league
   match and exist only as cup opposition — 16% of all player-appearances. The
   pooled slice already shows the ranking bias; at full scale it is 12,904 cup
   ties.

### Deliberately still not built

The sidecar; per-match home-side overrides; multi-host host rows (World Cup 2026
is absent from the host table, so its matches are all neutral); rating history
and `run_id`; any interface extracted from `DataLoader`; the GUI (item 17).

### Grill (2026-07-22) — eight decisions

The seven questions above were put in dependency order; one was retired before
being asked, and three more were added that the list had missed.

1. **All 70 competitions enter**, no `rating_eligible` flag — but the venue rule
   needed a third curated set first (see 5).
2. **Full batching**, not the per-match path. 630 competition-seasons exist, so
   an enumerated slice list could never reach the spine; four queries replace
   three-per-match. `loadEvents` keeps its signature and every domain rule.
3. **`Player` interning declined** — measured at ~200 MB, but 1,075 ids carry
   more than one name, so it would change CSV output for a saving 8.5 GB of
   heap makes unnecessary.
4. **The grid runs unparallelised.** Cost turned out to be a quadratic freeze in
   `MatchProcessor`, not the grid; once fixed, 81 cells take under 3 minutes on
   80,471 matches. 32 cores were available and not needed.
5. **Q5, the `h` anomaly, was retired before the grill reached it.** Not an
   anomaly: `GB1` 2024/25 was a genuinely unusual season at 51.6%, while the
   spine-wide domestic-league share is 55.8%. Increment 1 happened to pick the
   outlier.
6. **The anchor is measured on leagues only.** Pooled it is confounded by
   strength — the cup-only share is 48.3%, implying a home *dis*advantage of
   −0.66. See [ADR 0008](../docs/adr/0008-home-advantage-in-the-expectation.md).
7. **Base rate first, then a joint `(K0, H, h)` grid**, with `k` pinned at 0.10.
   The base rate depends on nothing and is free from the first run; the other
   two interact, so they are swept together rather than one at a time.
8. **The grid is scored from 2015-07-01** — new
   [ADR 0010](../docs/adr/0010-scoring-window.md). Q6's left-censoring
   diagnostic is superseded by it.

**Added to the list during the grill:** the loader's entry point (Q1/Q2 assumed
`SLICES` could reach the whole spine — it cannot); what increment 3's *gate*
even is, given that its purpose is to replace the constants the old gates were
pinned to; and a pre-registered census, predicted from SQL before any Java ran,
as the pass/fail condition.

### Outcome (2026-07-22) — DONE, every gate met

Landed in nine gated stages: (A) the freeze fix, byte-identical; (B1) the third
venue set; (B2a/B2b) batched lineups+length, then events; (B3) no-argument
`loadMatches()` and `Scope`; (C1) three defects the first full run exposed;
(C2) the base rate pinned per spine, spine-aware gates, league-only anchor;
(D1) the scoring window; (D2) the 81-cell grid and its winner.

**The pre-registered census reproduced exactly.** Predicted from SQL before the
first full run, and hit on every category:

| Quantity | Predicted | Actual |
|---|---|---|
| games loaded | 88,958 | 88,958 |
| **matches replayed** | 80,471 | **80,471** |
| no lineups / XI≠11 / no GK / two GKs | 7,761 / 717 / 6 / 3 | same |
| tripwire | 0 | 0 |
| goals credited | 223,810 | 223,810 |
| team-minutes | 14,610,840 | 14,610,840 |
| **base scoring rate** | 0.01532 | **0.01532** |
| venue HOME / AWAY / NEITHER | 79,795 / 8 / 668 | same |
| league-only anchor `h` | ≈2.33 | **2.32** |

ADR 0009's inherited "77,781 usable" was wrong and is corrected there.

**Three defects the full scale exposed, all fixed in C1:**

- **`CsvWriter` crashed on a null club name.** 86 clubs have none. The entire
  run — replay, grid, leaderboard — completed and then threw on its last line.
- **The skip report printed 576 lines**, because the club name was baked into
  the message the counter grouped by. `UnusableMatchException` now carries a
  short *reason* separate from its detail; four lines.
- **Extra time was read from constructed events, not source rows.** 26 matches
  that played 120 minutes got a 90-minute whistle. Amended in ADR 0009.

The extra-time fix was caught by increment 2's own test
(`theFaCupSliceIsFullOfExtraTime`, 32 → 33) — and investigating *why* confirmed
the change instead of reverting it: Stoke 3–3 Cardiff went to a shootout, so it
must have played extra time, but its only post-90 trace is a yellow card at 98'.

**The champion, and the four runs now pinned:**

| Run | Matches | Measured base rate | Log-loss |
|---|---|---|---|
| StatsBomb spine | 2,651 | 0.01473 | **0.6259** (byte-identical through all nine stages) |
| **TM full ingest — designated** | **80,471** | **0.01532** | **0.6502** windowed / 0.6508 whole |
| TM `GB1/2024` | 380 | 0.01630 | 0.6662 |
| TM `GB1 + FAC + AFAC / 2024` | 547 | 0.01592 | 0.6742 |

Winner `k = 0.10, K0 = 1.0, H = 4,000, floor = 0.05, h = 2.0`, interior on every
swept axis; venue-blind (`h = 0`) in the same run is 0.6551. **`K0` and `H` did
not move** — only `h`, 2.5 → 2.0. `Main` now defaults to `Scope.ALL`; the slice
list survives as the fast regression path (2 s against 12 s).

Full replay of 80,471 matches: **~12 s**. 81-cell grid: **~166 s**. 105 tests
green.

**Still not built:** the sidecar; per-match home-side overrides; multi-host host
rows; rating history and `run_id`; any interface extracted from `DataLoader`;
the GUI (item 17); item 16's unrated-opponent diagnostic (Q7 — a change to the
metric, not to ingestion).

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

**Split out (2026-07-23):** this item stays the *motivation* — pre-2013 depth,
international qualifiers, and the stoppage/running-score fidelity only a re-scrape
can restore. The *mechanism* (editing `transfermarkt-datasets`' `config.yml`
season and competition lists and re-running the scraper + dbt) and the weekly
freshness job are now items **26** and **27**.

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

**Framed per-player (2026-07-23, user):** the tool is wanted *per player* —
driven from item **25**'s per-player missing-data overview, so you clean the
matches that gap one specific career rather than trawling a global skip list of
~8,500. It is also the landing spot for item **26**'s loosened gate: matches
admitted "for manual check later" queue here. The sidecar-content prerequisite
above still holds.

**Placed by the item 26 grill (2026-07-23) as stage 4 — the GUI lands last.** It
is **decoupled** from the rating engine: it edits the sidecar only, and a release
takes effect on the next full batch replay, never inside the tool (the engine
stays the single producer of ratings; the GUI stays a testable data editor). Its
worklist is item **25**. Its prerequisite — a first real repair — is deliberately
manufactured by item 26 stage 3 (hand-fix one match, no GUI), so by the time the
GUI is built the sidecar's shape is already proven. A released match carries a
`draft`/`released` status; only `released` rates, so a half-finished entry is
safe. Decisions in [ADR 0009](../docs/adr/0009-transfermarkt-as-the-rating-spine.md)'s
2026-07-23 amendment.

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

**Grill done (2026-07-22), decisions in
[ADR 0011](../docs/adr/0011-impact-index-and-the-career-chart.md).** Taken as one
piece of work with item 8, because a rating cannot be plotted against time until
it is a rate rather than a total. Three facts came out of probing the snapshot
directly:

- **DOBs are full dates**, not year placeholders — only 343 of 50,100 fall on
  1 January. `players` has 50,149 rows and 50,100 carry one.
- **Ages behave** — 2.86M lineup rows spread 14 to 45, peaking at 24–25.
- **The relevance filter almost closes the coverage gap by itself.** 69,975 of
  114,893 players in lineups have no `players` row at all, but of the 17,030
  players past 1,000 minutes by the vendor's own count, **15** lack a DOB. The
  missing-DOB set and the too-few-minutes set are nearly the same people.

The grill also **narrowed what this item delivers**. Goalimpact's chart carries
two lines: a thick one (the rating at that time, using no future games — which
is exactly what our replay produces) and a thin dashed one (Peak Goalimpact,
projected from the population ageing curve). This item now lands the thick line
and the date-of-birth loading. The ageing curve itself — with the
selection-corrected within-player comparison it needs — becomes item 21.

An early proposal to plot **residuals per 90 taken before the update factor**
was put up and withdrawn: it measures surprise relative to expectation, so a
correctly rated player scores near zero however good he is. ADR 0011 records it
under considered options.

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

**Grill done (2026-07-22), decisions in
[ADR 0011](../docs/adr/0011-impact-index-and-the-career-chart.md).** Promoted
from low priority and merged with item 2: drawing a career chart forces the
scale question rather than leaving it theoretical, because `Value` is a total
and a total cannot be plotted against time. The shipped shape is the **Impact
index** — Value per 90 minutes of exposure, on a frozen scale with mean 100 and
15 points per standard deviation (measured 2026-07-22: mean 0.0087, sd 0.2395,
over 25,334 players past 1,000 minutes).

"Presentation only" turned out to be half right. The *stretch* is presentation;
the *division by exposure* is not, and it is the whole reason a rating can be
read as a level at all.

Four of Goalimpact's published landmarks are reproduced without being fitted to:
average 100, top 1% at 140 ("top-five European league"), ~top 0.4% at 150
("world class"), single best at 212 against their all-time top of ~200. A spread
of 20 points per standard deviation was tried first and puts the best player at
250. The Bundesliga-135 and 2.Bundesliga-116 reference points above are **not**
checkable yet — they need item 9's cross-division calibration to be trustworthy,
since a league whose players mostly play each other floats at a false average.

**Corrected the same day (2026-07-22) — the paragraph above is wrong, and is
kept for the record.** It divides Value by exposure. The first rating history
ever written refuted that on first contact: Value **converges** rather than
accumulating, so dividing it by ever-growing career minutes decays as
~1/exposure. Under the numbers above **Messi scores 102, van Dijk 96 and Müller
105**, while a reserve goalkeeper with 1,044 minutes scores 212. The evidence is
a fixed 234-player cohort followed through its own exposure — mean Value 11.54 →
13.54 → 15.38 → 16.04 across 5,000 → 10,000 → 20,000 → 30,000 minutes, a level
being approached. The earlier "flat rate at every career length" reading binned
*different players* by career length and so measured survivorship.

The shipped index is therefore **Value rescaled directly**: mean **1.8374**,
sd **7.1729** over the 25,334 eligible players, **20 index points per standard
deviation**. Chosen against Goalimpact's two countable landmarks rather than
fitted to them — 150 of our players clear 160 against their ~200 worldwide, and
our best is 196 against their all-time ~200. The top 20 reads Luis Díaz, Alaba,
Müller, Inácio, Neuer, Neres, Undav, Carvajal, Jesus, Foden, Hummels, Otamendi,
which is the eyeball gate ADR 0011 wrote for itself, passed on the second
attempt and failed on the first.

## 21. The ageing curve and Peak Impact (Goalimpact's second line)

**Why:** A Goalimpact chart carries two lines. Item 2 lands the thick one — the
Impact index at that moment, using no future games. This item is the thin dashed
one: **Peak Impact**, a player's projected ceiling, obtained by reading his
current index against the population's ageing curve. It is what turns the chart
from a record into a forecast, and it is the half of item 2's original ambition
(peak at ~26, an asymmetric curve rising faster than it declines) that ADR 0011
deliberately deferred.

**Data availability:** unblocked the moment ADR 0011's results file exists —
per-match rows plus dates of birth is exactly the input. Nothing further is
needed from the vendor.

**Design already grilled (2026-07-22), decisions not yet ADR'd.** Three
conclusions carried over from the item 2 grill and worth not re-deriving:

- **Build it from each player against his own younger self**, not from averaging
  different players at each age. The players still on a pitch at 34 are the ones
  good enough to have survived; everyone who declined retired, so a plain
  average per age answers "who gets selected" rather than "what happens to a
  person as he ages". Chain year-on-year steps instead, weighting each step by
  the smaller of the two years' minutes. Print the plain per-age averages
  alongside: the gap between the two lines *is* the selection effect, and
  showing it beats hiding it.
- **A year is birthday to birthday.** This spine's 65 competitions do not share
  a calendar — Scandinavian, Russian and Asian leagues are not on Europe's
  August-to-May season — so "a season" has no single meaning here while a
  birthday always does. Require ~900 minutes in both years for a step to count.
- **Left-censoring poisons the young end, and the 1,000-minute filter does not
  fix it.** The model rates an unseen player 0, which means *exactly average*
  rather than *unknown*, so a good newcomer banks an unpriced bonus until
  expectation catches up. That bonus lands at 18–20 for a genuine debutant and
  at 28–30 for a career already underway when the data starts in July 2013 — a
  fake peak in the middle of the curve. ADR 0011 lands an off-by-default knob
  that drops each player's first ~2,000 minutes, so one run can tell whether a
  peak at 19 is football or arithmetic. Glossary *Left-censored career* names
  the condition.

**Prerequisite:** item 2 / ADR 0011 stage 2 (the results file).

## 22. Career chart viewer: every player searchable

**Why:** The first viewer (2026-07-22, ADR 0011) is a self-contained HTML page
with the data baked into it, which caps it at **1,302 of 94,807 players** —
those past 20,000 career minutes, plus anyone past 3,000 minutes with an index
of 150 or better. That was the right shape for answering "does the line look
like football", and it is the wrong shape for use: the player you want to look
up is usually the one you cannot.

The constraint is that the page carries its own data. 1,302 careers sampled
monthly is 0.75 MB; all 94,807 would be tens of megabytes, and most of them are
cup opposition with a few hundred minutes and no line worth drawing.

**Options, none grilled yet:** (1) raise the embedded set to everyone past the
1,000-minute eligibility threshold — 25,334 players, still a big download but
the honest population, possibly with coarser sampling for long careers;
(2) serve the data instead of embedding it, which means the viewer stops being
a single file; (3) keep the page small and generate it per player on demand
from the results file.

**Prerequisite:** none — the results file already holds every row. This is a
delivery problem, not a data one.

**Also worth folding in when it happens:** the fixed y-axis clamps at 70 and
215, so a career that dips below 70 flattens against the floor without saying
so; and Goalimpact's second line (item 21) will need somewhere to live.

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

## 23. Club-team bands on the career chart

**Why (user, 2026-07-23):** the career chart (item 22,
[ADR 0011](../docs/adr/0011-impact-index-and-the-career-chart.md)) plots the
Impact index over time but shows nothing about *where* the player was, so a dip
that coincides with a transfer reads as unexplained. Add each contiguous spell
at a club as a tag with its own shaded background band along the horizontal
(time) axis, so the eye ties a rating change to a move.

**Data availability:** club-per-match is in the spine — a lineup names the club,
and `appearances`/`games` carry `club_id`. First thing to check: whether ADR
0011's results file already carries the club per player-match row or it must be
added. A "spell" is a run of consecutive matches at one club; decide up front how
to treat loans, mid-season moves, and interleaved national-team caps (band club
only, or club + country).

**Prerequisite:** item 2 / ADR 0011 results file; overlaps item 22's viewer.

## 24. Per-player match log — which matches moved the rating

**Why (user, 2026-07-23):** every match a player was on pitch for is a *rating
period* (glossary) that moved their Value at the whistle, but there is no way to
open one player and read the per-match ledger — this match, this residual, this
Value before and after. Wanted as an inspection tool to understand why a rating
sits where it does, and as the natural drill-down from the chart (item 22/23).

**Data availability:** low-cost — the ADR 0011 results file already holds
per player-match rows, so the Value delta per match is derivable; the residual
and the drained expectation behind it may need emitting alongside. This is a
*view* over what the full ingest (item 20) already produced, not a model change.
Complements item 25 exactly (matches that counted vs. matches that could not).

## 25. Per-player overview of matches missing data

**Why (user, 2026-07-23):** the full ingest (item 20) skips 8,487 of 88,958
matches — no lineups, XI≠11, no GK, two GKs — and more are missing outright
because the vendor never carried them. Today the skip report is a global
four-line count (item 20, C1). A player-scoped view — "these matches your player
was in did not count, and why" — is what tells you whether a given career is
under-measured by gaps, and it is the worklist the cleaning GUI (item 17)
consumes.

**Data availability:** the loader already raises `UnusableMatchException` with a
short reason per skipped match. Two kinds of gap must both be surfaced:
match **present-but-skipped** (group its would-be lineup by player — but note a
match skipped *for having no lineup* has no player rows to group by), and match
**absent entirely** (visible only as "the club played, the player has no row").
The second kind is the harder half and the one item 26 will grow.

**Grilled with item 26 (2026-07-23).** This item *is* stage 2 of item 26's
staging: the read-only per-player worklist. Present-but-skipped is the **certain**
tier (read the broken lineup, which names the player); absent-entirely is the
**maybe** tier (the player's club played that day, from `appearances.player_club_id`
by date — **not** `transfers`, too sparse at 4,345 players). Certain ships first,
its counts reconciling to the existing four-line skip report; maybe follows with
the GUI (stage 4). It is the worklist item 17 consumes.

## 26. Widen the match range — loosen the gate, and rebuild the spine wider

**Why (user, 2026-07-23, explicit):** "widen the range of matches. I don't care
if this makes us include matches that I will need to manually check afterwards."
Two independent levers:

1. **Loosen the ADR 0009 strict gate on the *existing* snapshot.** Admit some of
   the ~8,487 currently-skipped matches (e.g. XI≠11, missing GK) as best-effort
   replays *flagged for manual check*, instead of dropping them. Trades
   cleanliness for coverage, which the user explicitly accepts. Every admitted
   borderline match is still trusted in full by the residual model, so this needs
   its own mini-grill before building.
2. **Rebuild the snapshot itself wider** from
   [dcaribou/transfermarkt-datasets](https://github.com/dcaribou/transfermarkt-datasets).
   Its `config.yml` pins seasons **2012–2025** and **74 competition ids**; adding
   seasons and ids and re-running the scraper + dbt emits a bigger duckdb of the
   **same shape**, so the read path never learns it grew — the whole premise of
   ADR 0009. This is the mechanism item **15** named; item 15 stays the *why we
   want more* (pre-2013 depth, qualifiers, fidelity), this item is the *how*.

**Clarified by user (2026-07-23) — a third match state, not a looser gate.**
Lever 1 above is reframed: an incomplete match must **not** enter rating at all
until a human has checked it. Every match is *ingested and kept*, but a
skipped/incomplete one sits in a "waiting room" touching nobody's rating; you
inspect it in the GUI (item 17), correct it, and *release* it, and only then does
it count. So a rating only ever moves on a match that is either clean from the
vendor or fixed-and-approved — the strict gate stands *for rating*, but rejects
are no longer discarded. This is the sidecar (ADR 0009) doing what it was for.
The mini-grill this needs is scheduled.

**Data availability (guidance captured 2026-07-23):** the pipeline is two repos.
`transfermarkt-scraper` (scrapy; `-s <season>`, `-p <parents.json>`; crawl chain
confederations → competitions → clubs → players → appearances, plus a `games`
crawler) acquires raw JSON; `transfermarkt-datasets` (a dbt project) transforms it
into the curated `games`/`appearances`/`game_events`/`game_lineups` tables and
writes the duckdb. Widening pre-2013 hits item 15's hard caveat: **no lineups
exist before 2013**, so those seasons still cannot seed a career.

**Reading order for a fresh context:** CLAUDE.md → CONTEXT.md (the new *Sidecar*
and *Match state* terms) → ADR 0009's 2026-07-23 amendment (the sidecar's working
shape and the never-loosen rule) → this item → items 25 and 17. The design is
fully grilled; implement to it rather than re-opening the decisions.

**Grill done (2026-07-23), decisions amended into
[ADR 0009](../docs/adr/0009-transfermarkt-as-the-rating-spine.md).** The grill
was on lever 1 — the "third match state" the user reframed above — not on lever 2
(rebuilding wider, which is mechanical once the pipeline runs). Six decisions,
all in simple terms with a recommendation taken each time:

1. **The waiting room is recomputed, not stored.** The gate already emits the
   skip set every run; the sidecar holds only decisions (repairs + releases). No
   stored "needs-check" list to drift.
2. **Three states — Clean / Held / Released — rating in two.** A rating moves
   only on a match that is clean-from-vendor or released-by-hand. Held matches are
   ingested and visible but move nothing.
3. **Wide override.** The sidecar wins for any match it contains; the worklist
   only *surfaces* the Held ones. One rule, not two.
4. **Drafts allowed** — a `draft`/`released` status; only `released` rates, so a
   half-finished manual entry is as inert as a Held match.
5. **Per-player worklist is certain-then-maybe.** Certain = the player is in a
   broken lineup (free). Maybe = the match has no lineup but the player's *club*
   played that day — sourced from `appearances.player_club_id` by date (dense),
   **not** `transfers` (only 4,345 players, verified 2026-07-23). Build certain
   first.
6. **The GUI (item 17) is decoupled** — it edits the sidecar; a release takes
   effect on the next full batch replay, which item 4's absence already mandates.
7. **The gate is never loosened** (grilled 2026-07-23). Lever 1 does *not* auto-
   admit any imperfect category — verified that the "XI is not 11" matches spread
   evenly across 1–10 starters, so there are no near-complete near-misses worth
   waving through; loosening would only rate nonsense. Not-perfect always means
   Held. So "widen the range" = keep the strict gate for auto-rating, stop
   discarding the rejects, and let a human release them — never relax the gate.

**Staging (gate each):** (1) sidecar read path, inert — empty sidecar is
byte-identical, log-loss 0.6502, still 80,471 matches; (2) the read-only
per-player worklist = item **25**, certain tier, its counts reconciling to the
existing skip report; (3) **the first real repair** end to end — hand-fix one
broken match (no GUI), release it, census moves by exactly one, rest
byte-identical; (4) the GUI (item **17**) + the maybe tier. The GUI lands last on
purpose: stage 3 hand-makes the first repair ADR 0009 always said must exist
before item 17 is built.

### Stage 1 outcome (2026-07-23) — DONE, gate held

The inert sidecar read path. `TransfermarktLoader` gained an optional second
constructor arg — a sidecar DuckDB `ATTACH`ed read-only into the vendor
connection when the file exists — and reads the set of `status = 'released'`
game ids once. A released id is skipped in the four vendor queries and its rows
re-loaded from the sidecar's four mirror tables (`matches`, `game_lineups`,
`game_events`, `appearances`), so a released match is a **whole-match
replacement keyed by game id**; a draft is never in the released set, so it is
invisible and rates nowhere. The override is a Java skip-and-add rather than a
SQL `UNION`: staging is per game id and `Main` re-sorts the match list, so
sourcing one game wholly from one side keeps starter/event order intact. Both
vendor and sidecar rows go through the same `LineupRow`/`EventRow` mapping,
`buildMatch`, clock and `EventOrdering`, so a released match is not a special
replay — just a different source for the same shape. The usability gate still
applies uniformly: a release must be coherent (11 + goalkeeper) to rate.

Inertness is structural: `Main` passes a `SIDECAR` path with no file behind it
yet, so `hasSidecar` is false, `released` is empty, every guard is a no-op and
no sidecar query runs. Stage 3 becomes "drop a file at that path".

The physical sidecar schema (four mirror tables + `status`/`provenance`/
`commit_hash` control columns on `matches`) was a free implementation choice —
the grill pinned only the semantics — and stage 3 will cement it.

**Gate met.** 110 tests green, including three new `SidecarOverrideTest` cases
(released wins over the vendor; a draft does not rate; an empty sidecar behaves
exactly as the vendor — all exercised against the real snapshot, 0.7 s). The
designated `Scope.ALL` run is byte-identical: 80,471 of 88,958 replay, skip
census 717 / 7,761 / 6 / 3, venue HOME 79,795 / AWAY 8 / NEITHER 668, base rate
0.01532, champion log-loss **0.6502** windowed / 0.6508 whole, ship gate 0.6502
< 0.6551. Leaderboard unchanged.

**Still not built (this item):** stages 2–4 — the per-player worklist (item 25),
the first real repair, the GUI (item 17) + the maybe tier.

## 27. Weekly automated refresh of the spine database

**Why (user, 2026-07-23):** once the spine is self-rebuildable (item 26), keep it
current by pulling each week's new matches automatically — a scheduled job
(Windows Task Scheduler) that runs the scraper's `games`/`appearances` crawlers
for the current season on the chosen competitions, re-runs dbt to rebuild the
duckdb, and drops it where `Main` reads it (the hardcoded path from item 18).

**Design notes / caveats:**

- **robots.txt disallows bots** (item 15) — personal use, slow rate, cache. A
  weekly incremental (one season's latest fixtures) is small, unlike item 15's
  multi-year backfill left running for days.
- **Freshness only reaches what the vendor has entered** — lineups can lag a live
  match by hours, so a Monday job may miss a Sunday-night game's XI.
- **The rebuild must be atomic** — write a new file and swap, so `Main` never
  reads a half-written duckdb.
- **Item 4 (checkpoint / incremental replay)** is the eventual optimisation if a
  weekly *full* replay of an ever-growing spine gets slow. Not needed yet — the
  full 80,471-match replay is ~12 s today.

## 28. Career validation pass — are the adjusted ratings right? (Messi, Scott Brown)

**Why (user, 2026-07-23):** with the full ingest shipped (item 20), walk a few
whole careers by eye to judge whether the numbers are believable, and chase two
specific smells the user already flagged:

- **Messi looks undervalued.** Hypothesis: his peak years (~2008–2012) predate
  the 2013 lineup start, so the model meets an already-formed superstar and spends
  his opening seasons *discovering* him rather than watching him rise — a
  *Left-censored career* (glossary). The fix is item **15** (pre-2013 depth);
  confirming the diagnosis is the off-by-default first-~2,000-minutes drop from
  ADR 0011 / item **21**.
- **Scott Brown looks overvalued.** Hypothesis: the Scottish top flight is a
  rating island (item **9**) — Celtic dominate a division whose other clubs float
  at a false average, with too few *bridges* (European ties, cups, transfers) to
  price them, so beating under-rated opponents banks unearned residual — item
  **16**'s cup-minnow inflation in league form.

**Data availability:** none needed — the results file already holds both careers.
This item is the *validation* that turns the hunches into evidence; the actual
fixes live in items 9, 15, 16 and 21.
