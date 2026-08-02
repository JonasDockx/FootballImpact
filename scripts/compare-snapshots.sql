-- Item 30, decision 2: the DIAGNOSTIC half of the reproduction gate.
--
-- The gate itself is the ratings (scripts/compare-ratings.sql). This script does
-- not decide anything; it exists so that a failure says WHERE to look instead of
-- leaving 88,958 matches to search. Same trick as held_matches (item 29,
-- decision 3): one verdict recorded twice, so the two cannot drift.
--
-- Only the seven tables GoalImpact reads are compared. The other five curated
-- tables (transfers, player_valuations, club_games, countries, national_teams)
-- are deliberately out of scope: a difference there cannot reach a rating.
--
-- WHY THE COLUMNS ARE SPLIT IN TWO (measured 2026-07-29, item 30 stage 0).
-- The vendor's dbt pipeline is NOT deterministic. Two builds from identical raw
-- data, at the same commit, on the same machine, minutes apart, differ from each
-- other -- and the difference is confined to display labels:
--
--     games        url, home_club_name, away_club_name      ~900 rows
--     game_events  club_name                                ~350 rows
--     players      name, first_name, last_name, url, ...    ~3 rows
--     appearances  player_name, player_current_club_id       ~400 / ~30 rows
--
-- The cause is that a label is chosen by a window function over several raw
-- records, so where a club or player appears under two spellings the tie-break
-- is arbitrary once rows are not processed in a fixed order. "FC Aktobe" and
-- "FK Aktobe" are the same club; which one surfaces is a coin toss per build.
--
-- Every rating-bearing column is STABLE across builds -- game_id, date,
-- competition_id, club and player IDs, goals, event minutes and types -- and
-- game_lineups and appearances, the two tables a rating is actually computed
-- from, are identical row for row.
--
-- So: rating-bearing columns MUST match and a difference is a real defect.
-- Label columns MAY differ and are reported as a count, never as a failure. A
-- gate that demanded identical labels would fail for ever, on nothing.
--
-- The row hash is order-independent on purpose: dbt does not promise a stable
-- row order and a reordered table is not a changed table.
--
-- WHY THE FORMATION COLUMNS ARE EXCLUDED TOO (added 2026-08-02, stage 3).
-- games.home_club_formation and games.away_club_formation are not read from the
-- `games` scrape at all -- base_games joins them in from the TEAM SHEET. So a
-- fixture that gains a sheet in a backfill has its `games` row rewritten from
-- NULL to "4-4-2 Diamond" without one rating-bearing field moving. Left in, they
-- would report every newly-sheeted fixture as a defect, which on stage 3 is
-- 42,000 of them. They are counted in section 3 instead, where the count is the
-- interesting number: it should equal the fixtures the census says gained a
-- sheet.
--
-- Usage. The two ATTACH lines below name the PREVIOUS snapshot and the one just
-- built, and they are edited per comparison -- there is only ever one pair worth
-- comparing and hard-coding it keeps the run a single command:
--   duckdb -init scripts/compare-snapshots.sql -c ".quit"

.mode box

ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets-2012.duckdb'
  AS old (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets-stage3.duckdb'
  AS new (READ_ONLY);

.print "=== 1. row counts ==="
WITH o AS (
  SELECT 'games' AS t, count(*) AS n FROM old.games
  UNION ALL SELECT 'game_lineups', count(*) FROM old.game_lineups
  UNION ALL SELECT 'game_events',  count(*) FROM old.game_events
  UNION ALL SELECT 'appearances',  count(*) FROM old.appearances
  UNION ALL SELECT 'players',      count(*) FROM old.players
  UNION ALL SELECT 'clubs',        count(*) FROM old.clubs
  UNION ALL SELECT 'competitions', count(*) FROM old.competitions
), n AS (
  SELECT 'games' AS t, count(*) AS n FROM new.games
  UNION ALL SELECT 'game_lineups', count(*) FROM new.game_lineups
  UNION ALL SELECT 'game_events',  count(*) FROM new.game_events
  UNION ALL SELECT 'appearances',  count(*) FROM new.appearances
  UNION ALL SELECT 'players',      count(*) FROM new.players
  UNION ALL SELECT 'clubs',        count(*) FROM new.clubs
  UNION ALL SELECT 'competitions', count(*) FROM new.competitions
)
SELECT o.t AS "table", o.n AS old_rows, n.n AS new_rows, n.n - o.n AS delta
FROM o JOIN n ON n.t = o.t;

.print ""
.print "=== 2. THE CHECK THAT MATTERS: rating-bearing columns, which must be identical ==="
WITH parts AS (
  -- games without the three unstable label columns
  SELECT 'games' AS t,
         (SELECT count(*) FROM (
            SELECT * EXCLUDE (url, home_club_name, away_club_name,
                              home_club_formation, away_club_formation) FROM old.games
            EXCEPT ALL
            SELECT * EXCLUDE (url, home_club_name, away_club_name,
                              home_club_formation, away_club_formation) FROM new.games)) AS n
  UNION ALL
  SELECT 'game_events',
         (SELECT count(*) FROM (
            SELECT * EXCLUDE (club_name) FROM old.game_events
            EXCEPT ALL
            SELECT * EXCLUDE (club_name) FROM new.game_events))
  UNION ALL
  SELECT 'game_lineups',
         (SELECT count(*) FROM (SELECT * FROM old.game_lineups EXCEPT ALL SELECT * FROM new.game_lineups))
  UNION ALL
  -- player_current_club_id joins in from `players`, which is one of the tables
  -- the vendor builds non-deterministically -- it is where the player is TODAY,
  -- not who he played for in the fixture, and nothing in src/ reads it. Found on
  -- stage 3, where it was the whole of an otherwise clean appearances diff: 26
  -- rows, every other column identical, appearance_id sets equal on both sides.
  SELECT 'appearances',
         (SELECT count(*) FROM (
            SELECT * EXCLUDE (player_name, player_current_club_id) FROM old.appearances
            EXCEPT ALL
            SELECT * EXCLUDE (player_name, player_current_club_id) FROM new.appearances))
  UNION ALL
  SELECT 'clubs',
         (SELECT count(*) FROM (SELECT * FROM old.clubs EXCEPT ALL SELECT * FROM new.clubs))
  UNION ALL
  SELECT 'competitions',
         (SELECT count(*) FROM (SELECT * FROM old.competitions EXCEPT ALL SELECT * FROM new.competitions))
)
SELECT t AS "table", n AS unmatched_rows,
       CASE WHEN n = 0 THEN 'identical' ELSE 'DEFECT - investigate' END AS verdict
FROM parts;

.print ""
.print "=== 3. label churn: expected, reported, never a failure ==="
SELECT 'games.home_club_name' AS label, count(*) AS n
FROM old.games o JOIN new.games x USING (game_id)
WHERE o.home_club_name IS DISTINCT FROM x.home_club_name
UNION ALL
SELECT 'games.away_club_name', count(*)
FROM old.games o JOIN new.games x USING (game_id)
WHERE o.away_club_name IS DISTINCT FROM x.away_club_name
UNION ALL
SELECT 'players.name', count(*)
FROM old.players o JOIN new.players x USING (player_id)
WHERE o.name IS DISTINCT FROM x.name
UNION ALL
SELECT 'appearances.player_current_club_id', count(*)
FROM old.appearances o JOIN new.appearances x USING (appearance_id)
WHERE o.player_current_club_id IS DISTINCT FROM x.player_current_club_id
UNION ALL
-- Not label churn: a shared fixture that GAINED a team sheet. Zero on a
-- reproduction, and on a backfill it should equal the census's "games that
-- gained a sheet" restricted to fixtures both files already held.
SELECT 'games.formation filled in (backfill only)', count(*)
FROM old.games o JOIN new.games x USING (game_id)
WHERE o.home_club_formation IS NULL AND x.home_club_formation IS NOT NULL;

.print ""
.print "=== 4. added or lost fixtures (nonzero is expected only when widening) ==="
SELECT 'game_ids in old but not new' AS side, count(*) AS n
FROM (SELECT game_id FROM old.games EXCEPT SELECT game_id FROM new.games)
UNION ALL
SELECT 'game_ids in new but not old', count(*)
FROM (SELECT game_id FROM new.games EXCEPT SELECT game_id FROM old.games);

.print ""
.print "=== 5. version stamps ==="
SELECT 'old' AS file, commit_hash FROM old.version
UNION ALL SELECT 'new', commit_hash FROM new.version;
