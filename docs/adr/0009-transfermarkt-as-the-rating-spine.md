# Transfermarkt replaces StatsBomb as the rating spine; StatsBomb becomes the ruler

Ratings are computed from **one spine at a time**. The spine is now the
`dcaribou/transfermarkt-datasets` curated DuckDB snapshot; StatsBomb open data
is demoted to test fixtures and to the **calibration set** the spine is
measured against. The two are never pooled: the same match under two ID spaces
would be replayed twice, inflating exposure and double-counting residuals
(glossary *Spine*).

The trade is coverage for precision. StatsBomb ships a few thousand matches
skewed to showcase competitions and cannot produce a career curve for a Belgian
first-division player. The snapshot holds **88,958 games across 65 competitions**
— 30 first-tier leagues, 10 domestic cups, the UEFA club competitions and their
qualifiers, 5 finals tournaments, super cups — with lineups, substitutions,
goals and cards, which is exactly and only what the engine consumes
(ADR 0003). What it costs is second-level timing, which ADR 0007 made matter.
Keeping StatsBomb as a ruler turns that cost from a guess into a measurement:
replay the overlapping matches as two runs and read the divergence.

**Everything below is the Transfermarkt loader's implementation of the ADR 0004
boundary, not the domain's.** The engine keeps consuming `List<MatchEvent>` and
never learns which source it came from — the loader answers every source
question from its own evidence, exactly as ADR 0008 required of any future
source.

## What the snapshot can and cannot say

Probed directly rather than assumed:

- **Lineups start in 2013.** Season 2012 has all 5,700 games and no lineup for
  any of them. There is no pre-2013 career in this source, at all.
- **Stoppage time is destroyed.** The vendor's pipeline clamps added time onto
  the nominal minute: minute 45 carries 23,145 events against minute 44's
  7,505; minute 90 carries 92,986 against minute 89's 20,427. Minutes past 90
  occur only in extra time. The handover's "half length = 45 + largest stoppage
  minute observed" rule therefore cannot run — there is no `45+3` to find.
- **Transfermarkt itself already uses a nominal clock**: `minutes_played` is
  exactly 90 for 990,536 full-match starters and 91 for 185.
- **Own goals arrive scoreboard-oriented.** On an own-goal row `club_id` is the
  *beneficiary* — the scorer sits in the other club's lineup in 6,174 of 6,178
  checkable cases — which is the glossary *Goal* definition for free.
- **Red cards are recoverable** from `description`: `Red card` (9,897) and
  `Second yellow` (9,742). The leading `"3. Yellow card"` numbering is a
  *season* counter, not a within-match one.
- **Goalkeepers are near-free**: `position = 'Goalkeeper'` is present in all but
  334 of ~161,900 starting XIs.
- **There is no venue geography anywhere.** `stadium` is a bare name across
  2,968 distinct strings.
- **`game_event_id` is a hash**, so within a minute there is nothing to order by.
- **No qualifiers, no friendlies, no Nations League.** The five national-team
  entries are finals tournaments only — 742 games.

## Decisions

**The playing clock is nominal.** A half is 45 minutes, an extra-time half 15;
`MatchEnd` at 90, or 120 where `minutes_played = 120` marks extra time.
Not a concession: the base scoring rate is *measured* (ADR 0007) as goals ÷
team-minutes, so a nominal denominator produces a rate in the same units that
the drain then consumes — the convention cancels out of the expectation. Adding
a constant stoppage allowance would rescale both sides of one equation and
re-measure the rate proportionally lower, achieving nothing. Residual error is
real but symmetric: a substitute arriving at 88' in a match with six added
minutes is credited 2 rather than 8. Glossary *Playing clock* records this so
`MatchEnd(90, 0)` is never mistaken for elapsed time.

**Amended 2026-07-22 (item 19).** The extra-time rule above turned out to be
half of one. `appearances` is absent for **1,174 matches that plainly played
extra time** — nearly all national-team football, and half of the FA Cup's ties
in a single season — so detecting it from `minutes_played` alone puts the
whistle at 90 while events sit past it. Extra time is now detected from
**either** signal: an event past labelled minute 90, or `minutes_played >= 120`.
The quiet extra time the original reasoning protected against is real but small
— 28 matches leave no event trace at all — which is why both signals are kept
rather than one replacing the other. A tripwire now rejects any match whose
events outlive its whistle, as a skipped match with a reason.

**Amended again 2026-07-22 (item 20): "an event past minute 90" means a
*source row*, not a constructed event.** The first implementation scanned the
event list it had just built, which excludes every row the loader declines to
turn into an event — an ordinary yellow card, a substitution whose arriving
player has no name, a row whose club matches neither side. Where such a row was
the only thing recorded after minute 90, the whistle went at 90 on a match that
plainly played 120, and no tripwire fired because by construction there was no
post-90 *event* to catch. It cost **26 of 80,471 matches**, 1,560 team-minutes.
The loader now records the highest minute the source stamps on each match while
the rows are read, before any of them is judged. The clearest case is Stoke
3–3 Cardiff, FA Cup fourth round, 8 February 2025: it went to a penalty
shootout, so it must have played extra time, but its only trace past minute 90
is a yellow card at 98' — and `appearances` claims the longest anyone played
was 87 minutes.

**Every event of minute *m* lands at `(m−1)·60 + 30`** — the midpoint, so
placement error is a symmetric ±30 s — and **order lives in the list**, not the
timestamp. `MatchProcessor` reads sequentially and already tolerates
zero-length segments, so spreading events apart would only invent durations the
source never claimed.

**Same-minute ordering is a fixed convention: red card → goal → substitution**,
with one evidence-driven exception — if the goal's scorer is the `player_in_id`
of a same-minute substitution, the substitution jumps ahead. 16,321 goals share
a minute with a lineup change, across 11,532 games (13% of games, 6.6% of
goals); 545 are resolved by the exception. A goal stops play and the restart is
the classic moment to change, so goals-before-subs is a genuinely asymmetric
prior; red-before-goal is the DOGSO-and-penalty pattern (428 of 991 such
collisions are penalties) and close to a coin flip otherwise.

**Home side forks three ways, not two.** ADR 0008's "in national-team
competitions only geography decides" was true of what it was written against —
finals tournaments — and too broad as a statement about international football:

- **Club competitions**: trust the fixture label, except a round named exactly
  `Final` (343 games; two-legged finals are named `final 1st leg` /
  `final 2nd leg` and correctly untouched) and curated exceptions.
- **National-team finals tournaments**: the whole competition is at a chosen
  host, so the label cannot mean what it says — the host country's side is at
  home, everyone else neutral. Curated per competition-edition (~40 rows), with
  multi-host editions such as Euro 2020 falling back to per-match rows.
- **National-team qualifiers and league phases**: a genuine home-and-away
  fixture, so trust the label, exactly as for clubs. Displaced home games
  (sanctions, ground bans, war) are curated exceptions.

This needs **no venue geography at any scale**, which the source could not have
supplied anyway. It also fails safe: a missed exception mis-grants advantage in
one match, where a stadium→country table fails open everywhere at once as names
drift.

**Amended 2026-07-22 (item 20): a third curated set, for whole competitions.**
Running every competition rather than a slice exposed two the fork got wrong,
both hiding in the 1,214 games whose `competition_type` is null and which the
rule therefore treated as ordinary club football:

| Set | Says | Rows |
|---|---|---|
| `NEUTRAL_COMPETITIONS` | this competition is never anyone's home fixture | `KLUB`, `UKRS` |
| `NEUTRAL_ROUNDS` | this competition plays *this round* at a chosen ground | `(FAC, Semi-Finals)`, `(SUC, Semi-Finals)`, `(SCI, Semi-Finals)` |
| `SINGLE_MATCH_FINAL` | a one-off final, anywhere | round `= "Final"` |

The **FIFA Club World Cup** (`KLUB`, 148 replayable games) is a tournament at a
chosen host — Japan, Morocco, the UAE, Qatar, and 63 games in the USA in 2025 —
so the label names nobody. Its host is deliberately *not* resolved: most
entrants (Auckland City, Raja, Al-Ain) play in leagues this snapshot does not
carry, so `clubs.domestic_competition_id` cannot name their country. No
evidence, no advantage — the same posture already taken for an uncurated
tournament edition. The **Ukrainian Super Cup** (`UKRS`) is always at a neutral
ground, like the Community Shield. The Spanish and Italian super cup
semi-finals have been played in Saudi Arabia since 2020, while `SUC`'s
pre-2020 `final 1st leg` / `final 2nd leg` rows are genuine home-and-away and
stay `HOME`.

**Whole-competition facts get a whole-competition set**, checked first. `UKRS`
is why: nine of its ten rows are named `Final` and the tenth `final decider`,
which a round-scoped rule missed. Enumerating round names means re-editing the
set whenever the vendor renames one.

Across the full ingest the fork produces **HOME 79,795 / AWAY 8 / NEITHER 668**.

**A match is dropped only when it cannot form a coherent replay** — no lineups,
an XI that isn't 11, no unique goalkeeper. Everything else is handled per event:
a `minute = −1` stamp or a substitution with nobody arriving costs that event,
not the match's other ~1,000 player-minutes.

**Amended 2026-07-22 (item 20): the census above was wrong.** This ADR
originally recorded 7,925 / 553 / 291 and "leaves 77,781 games", figures that do
not reconcile even with each other — 88,958 − 7,925 − 553 − 291 is 80,189, not
77,781 — so the usable count came from a gate this document never describes. The
full ingest settles it. Predicted from SQL before the run and reproduced by it
exactly:

| Verdict | Games |
|---|---|
| **replays** | **80,471** |
| no lineups | 7,761 |
| XI is not 11 | 717 |
| no starting goalkeeper | 6 |
| two starting goalkeepers | 3 |
| post-whistle tripwire | 0 |

488 individual events are dropped without costing their match. The tripwire
firing zero times is the right answer rather than a dead check: the largest
labelled minute anywhere is 120 and the whistle is at 120, so it can only fire
if the vendor ships a minute past that.

The 593 genuine score/event disagreements are **kept and replayed**. (The
handover's feared 1,881 is mostly an artefact: 1,288 are penalty shootouts,
whose result Transfermarkt folds into `home_club_goals` and which the glossary
*Goal* excludes.) The cost is named rather than waved away — a missing goal
event understates that side's actual goal difference while its expected still
drains in full, so it takes a systematically negative residual on 0.67% of
matches. Losing those matches entirely is worse.

**Everything skipped is counted and written**, never printed and forgotten:
exposure drives the update factor (ADR 0006), so quietly thin competitions
manufacture false debutants.

**Corrections are match-level replacements, not field-level patches.** To fix a
match you materialise it into your own store, pre-filled from the vendor where
the vendor had anything, and your version wins outright. One rule — *a match is
either the vendor's or yours* — collapses the handover's `overrides` table,
manual-entry feed, row-level `source` precedence and `NEEDS_RESCRAPE` into a
single operation, and it is the only shape that can fix the largest failure
category, where there is no field to patch and 22 players must be authored from
nothing. Each materialised match carries free-text provenance and the vendor
`version.commit_hash` it was made against.

**Three files, three lifecycles.** The split follows how long each kind of fact
must live, which is the one thing that never changes:

| File | Contents | Lifecycle |
|---|---|---|
| vendor snapshot | Transfermarkt facts | read-only, replaced wholesale on refresh |
| sidecar | repairs, manual matches, curated home-side facts, issue log | precious, never auto-wiped |
| results | `rating_history`, run parameters, diagnostics | disposable, rebuilt at will |

DuckDB attaches all three in one connection and joins across them, so this
costs no ETL and no second database engine. Keeping repairs beside vendor rows
would guarantee eventually losing hand-made work to a refresh; keeping derived
output beside repairs would put it one rebuild bug away from the same loss.

**Amended 2026-07-23 (item 26): the sidecar's working shape, settled by grill.**
This ADR promised a sidecar but left its mechanics open; realising it — so that
incomplete matches can be *ingested and inspected without ever touching a
rating* — pinned five decisions:

- **A match has three states, and rates in two of them.** *Clean* (passes the
  usability gate → rates on vendor data), *Released* (present in the sidecar and
  approved → rates on the sidecar's copy), *Held* (fails the gate and is not in
  the sidecar → ingested and visible, but moves no rating). The invariant the
  user asked for: **a rating moves only on a match that is clean-from-vendor or
  released-by-hand** — nothing half-broken is ever trusted. **The gate is never
  loosened to auto-admit imperfect data** (grilled 2026-07-23): the 717 "XI is
  not 11" matches spread evenly across 1–10 starters rather than clustering at
  10, so there are no near-complete near-misses a looser rule would rescue — it
  would only wave through nonsense. Not-perfect always means Held.
- **The Held list is recomputed every run, never stored** — the same posture as
  left-censoring above. The gate already produces the skip set for free on every
  replay; storing a second copy only lets it drift. The sidecar holds *decisions*
  (repairs, releases), never *problems*.
- **The override is wide.** The sidecar wins for *any* match it contains, clean
  or broken — one rule, "if it's in your file, yours wins," extending the
  match-level-replacement decision above. The worklist merely *surfaces* Held
  matches; it does not limit what the override can reach.
- **A sidecar match carries a `draft`/`released` status, and only `released`
  rates.** A draft is as inert as a Held match, which lets a long manual entry
  (two lineups and a timeline authored from nothing) be saved half-finished
  without any risk of a partial match reaching a rating.
- **The repair GUI (item 17) is decoupled from the rating engine.** It edits the
  sidecar only; a release takes effect on the next batch replay — the full
  re-run that backlog item 4's absence already mandates (a released 2019 match
  slots into its 2019 place from scratch). The engine stays the single producer
  of ratings; the GUI stays a testable data editor.

Per-player attribution of Held matches (item 25) reads the *broken* lineup where
one exists, and otherwise the `appearances` table's `player_club_id` by date —
**not** `transfers`, which covers only 4,345 players against `appearances`'
per-match club on every row. Staged inert-first, gate byte-identical on an empty
sidecar; the first `released` row is the repair this ADR always said must come
before the tool.

**Amended 2026-07-23 (item 26, stage 4a): the "maybe" tier is really two tiers.**
The note above pinned a two-rung worklist — *certain* (named in a broken team
sheet) then *maybe* (the club played that day). Probing the 7,761 `no lineups`
Held matches before building split the second rung in two: **5,518 of them still
carry per-game `appearances` rows** — a real roster with minutes, median 14
players a club, 95% match-like — so those players are nameable *exactly*, not
guessed. Only the remaining **2,243** have neither team sheet nor appearances and
need the by-date inference the original note described. The worklist is therefore
a three-rung ladder — **certain → appeared → maybe** (glossary *Worklist tier*) —
and the `appearances`-by-date rule governs only the bottom rung. Decisions
settled by grill: the *appeared* and *maybe* lists are **two new tables** in the
disposable results DB beside `held_appearances`, rebuilt whole each designated
run; the loader records each `no lineups` Held match *as the gate throws it* (same
non-drift trick as the certain tier — the match set is the gate's verdict, names
are joined on afterwards, releases excluded for free); the *appeared* row keeps
the player's minutes, the *maybe* row keeps a "seen in N nearby matches" strength
count and a **±1-month** squad window. SQL does the set-shaped join, Java holds
the gate — unchanged from the division above. This tier is read-only and touches
no rating, so the run stays byte-identical (80,472 / 0.6502); the gate is that
the two lists **partition** the no-team-sheet matches exactly (7,761 = 5,518
appeared + 2,243 maybe), counts reconciling to a SQL prediction made first:
137,316 appeared rows (8,174 distinct players) and 9,550 maybe rows. The ±1-month
window finds a squad for only 435 of the 2,243 no-appearances matches; the other
1,808 have no other club game within a month, and widening to ±1 week (342
matches) or ±4 months (652) moves that little — most are genuinely isolated in
the snapshot (national teams, sparsely-covered clubs), which is why a modest,
accurate window is chosen over a wide, noisy one.

**SQL does set-shaped work, Java does per-match interpretation.** SQL joins,
unions the sidecar over the vendor, and computes the usability gate across all
matches at once. Java owns the clock, the ordering comparator, card
classification, home-side classification and event construction — the domain
judgement, unit-testable against fixtures without a database, and comparable
against the StatsBomb loader that does the same work the same way.

**Rating history is written for one designated run, not for the grid.** A full
run produces ~2.8M rows; `Main`'s 81-cell cross-check would produce 227M for a
search whose answer is one log-loss per cell. The grid writes scores; a
designated champion run writes history. `run_id` carries the run's parameters,
which is what turns the StatsBomb-vs-Transfermarkt comparison into a join.

**One Maven module.** The dependency direction the handover wanted a reactor to
enforce is already documented (ADR 0004) and already honoured — Gson sits on
the engine's classpath today and no engine class imports it. The split's real
payoff is JavaFX, not `duckdb_jdbc`, and moving classes between modules later
is mechanical.

**Amended 2026-07-23 (item 26, stage 4b): JavaFX arrived, and the module stays
one.** This decision named JavaFX as the split's only real payoff, and item 17
carried that forward as "this is when the three-module split earns its keep" — so
the question was deliberately re-opened when the repair GUI was grilled, and
**re-declined**. The reasoning above did not weaken: no engine class imports
JavaFX either, so the reactor would still enforce a rule nobody is breaking. What
the grill added is a cost the original decision could only guess at — JavaFX is
not a plain jar (platform-specific natives, its own launch and threading rules),
so landing it *and* a first reactor build together produces two build problems in
a stage whose actual work needs zero. JavaFX joins the existing pom via
`javafx-maven-plugin`, pinned at **21** to match `maven.compiler.release`; GUI
code lives in a new `com.goalimpact.gui` package, which holds no SQL exactly as
`data` holds no JavaFX. The split remains mechanical whenever `mvn test` gets
slow or someone actually imports JavaFX into the engine.

## Considered options

- **Pool StatsBomb and Transfermarkt (rejected).** Requires cross-provider
  identity matching and de-duplication of the overlap before a single rating is
  credible, and buys almost nothing: the StatsBomb matches are a subset of what
  Transfermarkt already covers.
- **Keep StatsBomb as the spine, Transfermarkt as reference data only
  (rejected).** Solves backlog item 2 (dates of birth) and nothing else. The
  career curve the project exists to draw needs the coverage, not the DOBs.
- **Own scraper for freshness (rejected).** The vendor rebuilds and republishes
  the snapshot **weekly** via GitHub Actions, so freshness is a download. The
  scraper alternative costs a Scrapy pipeline plus a Java reimplementation of
  the vendor's dbt normalisation, kept in sync forever, to avoid it. Scraping is
  deferred to a *depth and fidelity* project (pre-2013 seasons, international
  qualifiers, stoppage minutes, running scores) — and when it comes, running the
  vendor's own pipeline with a wider season range beats writing a parser,
  because the output shape stays identical and the Java read path never learns
  where the rows came from.
- **Copy everything into SQLite first (rejected).** 200 MB duplicated and a
  translation step that can silently drift, for no capability the snapshot
  doesn't already have. The vendor file *is* the raw layer the handover's
  three-layer design existed to build.
- **Corrections as compiled constants (rejected).** What `NEUTRAL_SEASONS` and
  `HOME_SIDE_OVERRIDES` do today. Breaks down at the hundredth correction, and
  each one needs a recompile.
- **Manual triage of same-minute collisions (rejected).** The handover's
  two-button triage screen assumed a handful of ambiguities. 16,321 is not a
  queue, it is a second job, and what it decides is whether one player of eleven
  carries one goal on a career-length average.
- **Runs refuse to start on unresolved errors (rejected).** Sound where
  breakages are yours and fixable; unworkable here, where 7,925 games have no
  lineup anywhere in the source and no amount of triage produces one. A hard
  gate would never let a run start.
- **Curate stadium → country (rejected).** Offered for non-tournament
  internationals. Unnecessary once qualifiers are recognised as genuine
  home-and-away fixtures, and it rots silently as 2,968 stadium names drift.
- **A constant stoppage allowance on the nominal clock (rejected).** Rescales
  both sides of the same equation; provably does nothing.
- **A warm-up pass over 2013–2014 to seed ratings (rejected).** ADR 0005's
  single sequential pass is what makes every rating causally honest — a rating
  on any date used only matches before it. Seeding 2013 from 2014 would
  contaminate every run forever to fix a burn-in that is a fixed, one-time cost
  at the window's edge and shrinks as the data grows.
- **Dropping domestic cups to dodge unrated opponents (rejected).** Loses 12,904
  matches and doesn't work — UEFA qualifying has the same problem. Rating only
  matches where both sides have league football was also declined: it discards
  70% of cup ties to dodge a bias nobody has measured yet.
- **Three Maven modules now (rejected).** Enforces a rule that isn't being
  broken, and substitutes a build-tooling problem for a domain one. *Re-opened
  and re-rejected 2026-07-23 when JavaFX actually landed (item 26, stage 4b) —
  see the amendment under "One Maven module".*
- **A `rating_eligible` flag per competition (deferred).** Designed to exclude
  friendlies and youth football. This snapshot contains neither — all 65
  competitions are competitive senior football. Build it when something needs
  excluding.

## Consequences

- **The glossary needed almost no change, which is the point.** *Home side* was
  defined in terms of "genuinely playing at its own venue" with no mechanism
  named, so swapping a stadium-country test for a host-country test did not
  touch the concept — only a sentence that had leaked an implementation. New
  terms: *Spine*, *Playing clock*, *Left-censored career*. The
  StatsBomb-specific scope paragraph was rewritten.
- **ADR 0008 needs no amendment.** It already said the national-teams-vs-clubs
  fork was "the StatsBomb loader's implementation, not the domain's", and that
  every future source must answer the venue question from its own evidence.
  This is that.
- **The base scoring rate must be re-measured.** ADR 0007 pinned 0.01473 goals
  per team-minute on 7,496 goals over a Barcelona-heavy StatsBomb slice, with an
  explicit instruction to re-measure "when large new eras/competitions land".
  78,000 matches on a nominal clock is exactly that, and the nominal denominator
  changes the units.
- **`h` and the (K0, H) grid are pinned to StatsBomb's population** and are not
  transferable on faith. The h ≈ 2.69 home-goal-share anchor should be
  recomputed over the new spine before the grid is re-run.
- **Full replay is the only mode.** Backfilling earlier matches invalidates
  every rating after them, so backlog item 4's checkpointing must stay unbuilt.
  ~2.3M events is seconds of Java.
- **Left-censoring is derived, never stored.** The handover called first-seen
  date irreproducible schema; under always-full-replay it is recomputed every
  run, so a backfill to 2008 reclassifies Iniesta by itself and promotes
  whoever debuted in 2008 to the new edge.
- **The unrated-opponent bias is accepted and instrumented, not fixed.** 2,501
  of 3,274 clubs never play a league match in this data — they exist only as cup
  opposition, and account for 512,380 lineup rows, 16% of all player-appearances.
  A fourth-tier side is rated 0, which in this model means *exactly average*
  rather than *unknown*, so the strength gap at kickoff is zero and beating them
  4-0 pays a full +4 of unearned residual. They do sink afterwards — ADR 0006
  gives them the largest update factor — but they often never play again, so the
  correction protects nobody and is never reused. This is a **ranking bias in
  favour of deep cup runs**, not a level shift. A per-match diagnostic reports
  how much of each player's residual came from unrated opposition; the fix is
  backlog item 9's prior for debutants, and it is a change to the metric, not to
  ingestion.
- **The age curve covers ~90% of appearances.** 2.86M of 3.18M lineup rows
  resolve to a date of birth; 69,943 players appear in lineups with no row in
  `players` at all. They still rate and still act as opponents; they cannot be
  plotted against age.
- **`duckdb_jdbc` must be pinned to a version that reads the published file.**
  A "count the games" smoke test is the first thing to write, so a storage-format
  mismatch fails loudly on day one rather than three layers deep.
- **The sidecar ships empty.** Its first row should be a repair that was actually
  needed, not a table guessed at in advance.
- **Staged landing.** Increment 1 is a vertical slice on **Premier League season
  2024** (`GB1`, `season = '2024'`) — 380 matches, all 380 clean on the gate,
  1,115 goals, 3,207 substitutions, 1,610 cards, 2024-08-16 to 2025-05-25.
  Gate: the StatsBomb path still yields log-loss 0.6259 with pinned knobs; every
  match either replays or is counted as skipped with a reason; and the
  leaderboard is recognisable by eye — the check no test provides and the one
  that catches a broken clock, ordering or venue rule in seconds.
- **The full ingest landed 2026-07-22 (item 20), and every constant above is
  now measured on it.** 80,471 matches, 2013-07-02 to 2026-07-06, 94,807 rated
  players. Base scoring rate **0.01532** ([ADR 0007](0007-time-integrated-residual.md)),
  home advantage **`h = 2.0`** against a league-only anchor of 2.32
  ([ADR 0008](0008-home-advantage-in-the-expectation.md)), `K0 = 1.0` and
  `H = 4,000` reconfirmed unchanged, champion log-loss **0.6502**. The full
  ingest is now the **designated run** — `Scope.ALL` is `Main`'s default, and
  the slice list survives as the fast regression path.
- **Loading is fully batched, and it had to be.** `loadMatches` was per
  competition-season and there are **630** of them, so an enumerated slice list
  could never reach the whole spine. Four queries now fetch every row a
  predicate's matches will need — `games`, `game_lineups`, `game_events`,
  `appearances` — grouped by game id in Java and drained as each match is
  consumed. `loadEvents` keeps its signature and every line of domain
  judgement; it simply stopped touching the database, and no longer declares
  `SQLException`. The batch unit is the same predicate `loadMatches` was given,
  so no caller has to remember to prefetch. `position`, `type` and
  `description` are reduced to booleans as rows are read — the same Java rules,
  run earlier, keeping 4.4M strings off the heap. This cut the loader's own
  cost roughly **4×**.
- **Memory was never the constraint; a quadratic freeze was.** `MatchProcessor`
  copied the *entire* tallies map twice at the start of every match, which is
  `matches × players`: 1.6M map writes over one season and about **8.9 billion**
  across the full spine, per replay, per grid cell. Invisible at 547 matches,
  dominant at 80,471. Freezing only the ~30 players who appear in the match is
  provably identical — `preMatch` is read only through `Lineup`, and the frozen
  exposures only for ids in `matchResiduals`, both of which hold on-pitch
  players. A lazy snapshot will not do: `minutes()` is mutated mid-match by
  `leavePitch`, so exposures must be snapshotted eagerly. Afterwards a full
  replay of 80,471 matches takes seconds and an 81-cell grid under three
  minutes, on a default 8.5 GB heap with peak usage under 1 GB. **No
  parallelisation was needed.**
- **`Player` interning was measured and declined.** Collapsing ~2.3M `Player`
  instances to the 114,893 distinct ids would save ~200 MB, and is safe in
  principle — `Player` and `Team` are records with value equality, the codebase
  contains no identity comparisons on them, and tallies are keyed on id. But
  **1,075 player ids carry more than one name** in `game_lineups`, so interning
  would take whichever name was seen first, changing leaderboard and CSV output
  for a saving that 8.5 GB of headroom makes unnecessary. (`Team` is clean —
  no club has an inconsistent name — but there is no reason to intern one and
  not the other.)
