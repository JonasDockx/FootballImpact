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

**A match is dropped only when it cannot form a coherent replay** — no lineups
(7,925 games), an XI that isn't 11 (553), no unique goalkeeper (291). Everything
else is handled per event: a `minute = −1` stamp (1,239 games) or a substitution
with nobody arriving (612) costs that event, not the match's other ~1,000
player-minutes. A strict all-or-nothing gate leaves 77,781 games.

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
  broken, and substitutes a build-tooling problem for a domain one.
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
