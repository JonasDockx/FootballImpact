-- Item 9: re-measuring the island, now that item 16 has levelled the table.
--
--   duckdb < scripts/island-remeasure.sql
--
-- #43 sequenced this: the island is re-measured AS SOON AS item 16 lands, and
-- the FORM of the fix is not chosen until after ADR 0013's pass 2. This file is
-- the first half only. It measures; it proposes nothing, and it derives no
-- constant. Sibling of scripts/unpriced-seed.sql and
-- scripts/model-bias-diagnostics.sql, whose prelude it repeats rather than
-- imports (DuckDB has no include, and a diagnostic that cannot be run in one
-- command is a diagnostic nobody runs).
--
-- WHY THIS FILE READS TWO RESULTS FILES. #9 records the suspicion that "much of
-- the island may not survive item 16" - Scottish football's whole net rating
-- gain was SMALLER than what its clubs collected off unpriced opposition (227%
-- on #39), so pricing the minnows might deliver part of item 9 in Scotland for
-- free. Every recorded island figure predates the seed, so quoting them at a
-- seeded run would compare two populations and call the difference a finding.
-- Both files here are the SAME 85,050-match spine, 2012-07-09 to 2026-07-06,
-- and the same grid cell; they differ in the seed and in nothing else:
--
--   pre  goalimpact-results.bak-2012-20260801.duckdb  ...-h2.00        seed 0
--   post goalimpact-results.duckdb                    ...-h2.00-s2.58  seed 2.58
--
-- That is ADR 0014 rule 2's same-run baseline used as a MEASUREMENT rather than
-- as a gate. Nothing here gates anything.
--
-- THE `pre` FILE IS NOT ONE OF ADR 0009'S, and this file is the only thing that
-- makes it look permanent. ADR 0009 calls the results file disposable and
-- rebuilt at will; the backup above is a hand-kept copy of it from before the
-- seed landed, so a machine without it cannot run this script. It is
-- REGENERABLE rather than precious: item 16's mechanism has a pinned off-arm
-- (a34015f made the run-id's seed-off branch real), so
--
--     mvn compile exec:java   with the seed cell at 0.00
--
-- reproduces it, and the run id it writes - ...-h2.00, no -s suffix - is what
-- the guard below checks for. Say so rather than leave a reader to discover
-- that a diagnostic depends on a file nothing documents.
--
-- THE SNAPSHOT MUST BE THE ONE BOTH RUNS WERE RUN AGAINST - it is joined to for
-- fixtures and lineups, so a mismatch silently drops matches rather than
-- failing. That is DataFiles.SNAPSHOT, which item 30 stage 3 deliberately has
-- not moved: the cut-over to the wide file is stage 4's, after pass 3.
--
-- EVERY NUMBER BELOW BELONGS TO A POPULATION (#39, ADR 0014 rule 3). A wider
-- spine will not reproduce these figures, and that is correct behaviour rather
-- than a bug - the whole point of item 9 is that the answer moves when bridges
-- arrive. Pass 2 brings the qualifiers and ~18 domestic cups that are the only
-- competitive fixtures where the leagues most at risk of being islands meet
-- European ones, so THESE NUMBERS ARE A WAYPOINT.

ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb'                    AS post (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.bak-2012-20260801.duckdb'  AS pre  (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb'                AS tm   (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb'                 AS sc   (READ_ONLY);

-- Guard: the two runs must be the same spine, or every before/after below is a
-- comparison of populations wearing the labels of a comparison of models. Equal
-- COUNTS are not enough - two files can hold 85,050 matches each and not the
-- same 85,050 - so the match_id sets are compared, and each run states its date
-- range because ADR 0014 rule 3 requires every quoted number to.
.print === guard: the two runs, which must differ only in the seed ===
SELECT 'pre' AS run, run_id, count(DISTINCT match_id) AS matches,
       min(match_date) AS first_match, max(match_date) AS last_match
FROM pre.rating_history GROUP BY 2
UNION ALL
SELECT 'post', run_id, count(DISTINCT match_id), min(match_date), max(match_date)
FROM post.rating_history GROUP BY 2;

-- Zero on both counts, or nothing below is a before/after. Grouping by run_id
-- above rather than selecting it as a scalar subquery is deliberate: a file
-- holding two runs would print two rows here instead of silently passing.
.print === guard: matches in one run and not the other, which must be 0 and 0 ===
SELECT (SELECT count(*) FROM (SELECT DISTINCT match_id FROM pre.rating_history
        EXCEPT SELECT DISTINCT match_id FROM post.rating_history)) AS only_in_pre,
       (SELECT count(*) FROM (SELECT DISTINCT match_id FROM post.rating_history
        EXCEPT SELECT DISTINCT match_id FROM pre.rating_history))  AS only_in_post;

CREATE TEMP TABLE replayed AS SELECT DISTINCT match_id FROM post.rating_history;

CREATE TEMP TABLE fixture AS
SELECT r.match_id,
       coalesce(s.competition_id,   CAST(g.competition_id AS VARCHAR)) AS competition_id,
       coalesce(s.competition_type, g.competition_type)                AS competition_type,
       coalesce(s.home_club_id,     CAST(g.home_club_id AS BIGINT))    AS home_club_id,
       coalesce(s.away_club_id,     CAST(g.away_club_id AS BIGINT))    AS away_club_id
FROM replayed r
LEFT JOIN tm.games   g ON CAST(g.game_id AS BIGINT) = r.match_id
LEFT JOIN sc.matches s ON s.game_id = r.match_id;

-- ...and the join the header warns about, which fails by dropping rather than
-- by erroring. A fixture with no club ids is a replayed match the snapshot does
-- not carry, which means the wrong snapshot is attached.
.print === guard: replayed matches the snapshot cannot place, which must be 0 ===
SELECT count(*) AS fixtures_without_clubs FROM fixture
WHERE home_club_id IS NULL OR away_club_id IS NULL;

CREATE TEMP TABLE lineup AS
SELECT match_id, player_id, any_value(club_id) AS club_id FROM (
  SELECT r.match_id, CAST(l.player_id AS BIGINT) AS player_id, CAST(l.club_id AS BIGINT) AS club_id
  FROM sc.game_lineups l JOIN replayed r ON r.match_id = l.game_id
  UNION ALL
  SELECT r.match_id, CAST(l.player_id AS BIGINT), CAST(l.club_id AS BIGINT)
  FROM tm.game_lineups l JOIN replayed r ON r.match_id = l.game_id
  WHERE NOT EXISTS (SELECT 1 FROM sc.matches s WHERE s.game_id = l.game_id)
) GROUP BY 1, 2;

-- League football as ClubPools.java reads it, INCLUDING item 16's two
-- corrections: COL1 / EJPL / POBE / BPO4 are league football that the vendor's
-- competition_type does not say so for, and a national side plays no league but
-- is nobody's minnow. TransfermarktLoader.LEAGUE_COMPETITIONS is the authority
-- and this is a copy - there is no way to share a constant across Java and SQL.
-- COL1 matters here more than anywhere: #16 found the Colombian first division
-- filed with no competitions row at all, and without this line all 20 of its
-- clubs read as cup minnows when what they are is the textbook island.
CREATE TEMP MACRO is_league(ct, cid) AS
  ct = 'domestic_league' OR cid IN ('COL1', 'EJPL', 'POBE', 'BPO4');

CREATE TEMP TABLE club_leagues AS
SELECT club, list(DISTINCT competition_id) AS leagues FROM (
  SELECT home_club_id AS club, competition_id FROM fixture WHERE is_league(competition_type, competition_id)
  UNION ALL
  SELECT away_club_id,          competition_id FROM fixture WHERE is_league(competition_type, competition_id))
GROUP BY 1;

CREATE TEMP TABLE national_teams AS
SELECT DISTINCT club FROM (
  SELECT home_club_id AS club FROM fixture WHERE competition_type = 'national_team_competition'
  UNION ALL
  SELECT away_club_id        FROM fixture WHERE competition_type = 'national_team_competition');

-- The league a club BELONGS to, for grouping: the one it plays most. A club
-- promoted mid-run has two, and argmax picks where it spent its football.
CREATE TEMP TABLE club_league AS
SELECT club, arg_max(comp, n) AS league FROM (
  SELECT club, competition_id AS comp, count(*) AS n FROM (
    SELECT home_club_id AS club, competition_id FROM fixture WHERE is_league(competition_type, competition_id)
    UNION ALL
    SELECT away_club_id, competition_id FROM fixture WHERE is_league(competition_type, competition_id))
  GROUP BY 1, 2) GROUP BY 1;

-- BRIDGE, as ClubPools.java derives it and ADR 0014 gates on it: the two clubs
-- share no league, so the strength gap the prediction reads is a gap BETWEEN
-- pools. Two national sides are NOT a bridge here - ADR 0014's consequence,
-- stated rather than reconciled with CONTEXT's player-level sense, because no
-- club-level mispricing moves that prediction.
CREATE TEMP TABLE match_bridge AS
SELECT f.match_id,
       NOT (f.home_club_id IN (SELECT club FROM national_teams)
            AND f.away_club_id IN (SELECT club FROM national_teams))
       AND len(list_intersect(coalesce(h.leagues, []), coalesce(a.leagues, []))) = 0 AS is_bridge
FROM fixture f
LEFT JOIN club_leagues h ON h.club = f.home_club_id
LEFT JOIN club_leagues a ON a.club = f.away_club_id;

-- Both runs in one table, so every query below reads a before and an after off
-- the same rows rather than being written twice.
CREATE TEMP TABLE walk AS
SELECT * FROM (
  SELECT '1 unseeded' AS run, h.player_id, h.match_id, f.competition_id,
         l.club_id AS own_club, b.is_bridge,
         h.rating_before, h.residual, h.minutes_played,
         h.rating_after - h.rating_before AS value_gained
  FROM pre.rating_history h
  JOIN fixture f      ON f.match_id = h.match_id
  JOIN match_bridge b ON b.match_id = h.match_id
  LEFT JOIN lineup l  ON l.match_id = h.match_id AND l.player_id = h.player_id
  UNION ALL
  SELECT '2 seeded', h.player_id, h.match_id, f.competition_id,
         l.club_id, b.is_bridge,
         h.rating_before, h.residual, h.minutes_played,
         h.rating_after - h.rating_before
  FROM post.rating_history h
  JOIN fixture f      ON f.match_id = h.match_id
  JOIN match_bridge b ON b.match_id = h.match_id
  LEFT JOIN lineup l  ON l.match_id = h.match_id AND l.player_id = h.player_id)
WHERE own_club IS NOT NULL;

CREATE TEMP TABLE club_strength AS
SELECT run, match_id, own_club AS club_id, avg(rating_before) AS mean_peak
FROM walk GROUP BY 1, 2, 3;

-- WHAT THE OPPOSITION WAS, in the only three kinds that matter here. "Bridge"
-- on its own is too coarse to measure item 9 with: a cup tie against a
-- non-league club and a European tie against Bayern are both bridges, and they
-- are opposite problems. Only the middle row below is item 9's.
--
--   own pool      the two clubs share a league. Not a bridge; a whole-league
--                 mispricing moves both sides equally and is invisible here,
--                 which is ADR 0014's entire argument.
--   other league  a bridge against a club that IS priced somewhere else. THE
--                 ISLAND TEST: the only fixtures where one pool's level is
--                 measured against another's.
--   unpriced      a bridge against a club this run never sees play league
--                 football. Item 16's population, and its RESIDUE after the
--                 seed - reported here to keep it out of the row above rather
--                 than to re-measure it.
--
-- National-team fixtures are their own row and are excluded from the island
-- test: ADR 0014 already rules that two national sides are not a bridge, and a
-- national side has no league to be an island in.
CREATE TEMP TABLE unpriced AS
SELECT DISTINCT club FROM (
  SELECT home_club_id AS club FROM fixture UNION ALL SELECT away_club_id FROM fixture)
WHERE club NOT IN (SELECT club FROM club_leagues)
  AND club NOT IN (SELECT club FROM national_teams);

CREATE TEMP TABLE opposition AS
SELECT w.run, w.player_id, w.match_id,
       CASE WHEN w.own_club IN (SELECT club FROM national_teams)
              OR opp IN (SELECT club FROM national_teams)      THEN '4 national team'
            WHEN NOT w.is_bridge                               THEN '1 own pool'
            WHEN opp IN (SELECT club FROM unpriced)            THEN '3 unpriced'
            ELSE                                                    '2 other league'
       END AS opp_class
FROM (SELECT w.*, CASE WHEN w.own_club = f.home_club_id THEN f.away_club_id
                       ELSE f.home_club_id END AS opp
      FROM walk w JOIN fixture f ON f.match_id = w.match_id) w;

-- ADR 0011's pinned scale. Measured on the 2013 population, NOT on the runs
-- this file reads - the ruler must not change length, so it stays put.
CREATE TEMP MACRO idx(v) AS 100 + 20 * (v - 1.8374) / 7.1729;

-- The same ruler applied to a DIFFERENCE rather than to a level: an amount of
-- rating gained is a gap on the scale, so the offset drops out and only the
-- gain survives. Named rather than inlined so ADR 0011's constants live in one
-- place here, the way ImpactIndex holds them for the viewer.
CREATE TEMP MACRO idx_delta(v) AS 20 * v / 7.1729;

-- WHAT `rating_before` IS, and why nothing below calls it an Impact index.
-- Since ADR 0016 the stored number is PEAK IMPACT - the player's estimated
-- best - and CONTEXT's Impact index is that less the Ageing curve's penalty.
-- On both runs this file reads, item 21 stage 1's curve is pinned FLAT, so the
-- two coincide to the digit and every figure below would be unchanged either
-- way. They are named `peak` regardless, because a column that is only
-- accidentally right is a trap for whoever reads this after stage 2 turns the
-- curve on.


-- ===========================================================================
-- 1. HOW MUCH BRIDGE THERE IS AT ALL.
--
-- The island is a shortage of bridges before it is anything else, so this is
-- the denominator every number below sits on. Pass 2 is expected to move it.
-- ===========================================================================
.print === 1. bridge share of the run ===
SELECT sum(CASE WHEN is_bridge THEN 1 ELSE 0 END) AS bridge_matches,
       count(*)                                   AS matches,
       round(100.0 * sum(CASE WHEN is_bridge THEN 1 ELSE 0 END) / count(*), 1) AS pct
FROM match_bridge;


-- ===========================================================================
-- 2. WHERE EVERY LEAGUE FLOATS, BEFORE AND AFTER THE SEED.
--
-- #32 stated the island as "a 15-point band across every top division on
-- earth". This is that band re-read on both runs. The question item 9 is
-- asking: did pricing the minnows PULL THE LEAGUES APART (the seed took back
-- unearned mass, so leagues that ate the most should fall furthest), or did it
-- leave the ordering intact and simply shift everyone?
-- ===========================================================================
CREATE TEMP TABLE league_float AS
SELECT cs.run, f.competition_id AS league,
       count(DISTINCT f.match_id) AS matches,
       idx(avg(cs.mean_peak))     AS mean_peak_index
FROM fixture f
JOIN club_strength cs ON cs.match_id = f.match_id
WHERE is_league(f.competition_type, f.competition_id)
GROUP BY 1, 2 HAVING count(DISTINCT f.match_id) > 500;

.print === 2. mean club peak index per league, unseeded vs seeded ===
SELECT p.league, p.matches,
       round(u.mean_peak_index, 1)                     AS unseeded,
       round(p.mean_peak_index, 1)                     AS seeded,
       round(p.mean_peak_index - u.mean_peak_index, 1) AS moved
FROM league_float p JOIN league_float u ON u.league = p.league AND u.run = '1 unseeded'
WHERE p.run = '2 seeded'
ORDER BY 4 DESC;

.print === 2b. the band itself, which is the island stated as one number ===
SELECT run, count(*) AS leagues,
       round(min(mean_peak_index), 1)               AS lowest,
       round(max(mean_peak_index), 1)               AS highest,
       round(max(mean_peak_index) - min(mean_peak_index), 1) AS band,
       round(stddev_samp(mean_peak_index), 2)       AS sd
FROM league_float GROUP BY 1 ORDER BY 1;


-- ===========================================================================
-- 3. THE DEFICIT TEST - the island measured on the pitch rather than on the
--    rating.
--
-- A pool priced above its true level should FAIL TO MEET its expectation
-- whenever it plays outside itself, and meet it inside. So per league: residual
-- per 90 in bridge matches against residual per 90 in everything else. A league
-- with a large negative bridge figure is carrying rating it cannot defend, and
-- the size of that number is what any item 9 fix has to close.
--
-- Read the SPREAD, not the level: a run-wide offset is the model's, not any
-- league's.
-- ===========================================================================
.print === 3. run-wide: residual per 90 by what the opposition was ===
SELECT o.opp_class, w.run,
       round(sum(w.minutes_played) / 90.0, 0)              AS full_games,
       round(90 * sum(w.residual) / sum(w.minutes_played), 4) AS resid90
FROM walk w JOIN opposition o ON o.run = w.run AND o.match_id = w.match_id AND o.player_id = w.player_id
WHERE w.minutes_played > 0
GROUP BY 1, 2 ORDER BY 1, 2;

.print === 3b. THE ISLAND TEST: residual per 90 against OTHER PRICED LEAGUES, per league ===
-- A pool priced above its true level fails to meet its expectation whenever it
-- plays a pool that is priced elsewhere, and meets it at home. Read the SPREAD
-- across leagues, not the level: a run-wide offset is the model's, not any
-- league's. `own_pool` is the control and should sit near zero everywhere.
SELECT cl.league,
       round(sum(w.minutes_played) / 90.0, 0) AS full_games,
       round(100.0 * sum(CASE WHEN o.opp_class = '2 other league' THEN w.minutes_played ELSE 0 END)
             / sum(w.minutes_played), 1)      AS pct_min_vs_other_leagues,
       round(90 * sum(CASE WHEN o.opp_class = '2 other league' THEN w.residual ELSE 0 END)
             / nullif(sum(CASE WHEN o.opp_class = '2 other league' THEN w.minutes_played ELSE 0 END), 0), 3)
                                              AS resid90_vs_other_leagues,
       round(90 * sum(CASE WHEN o.opp_class = '1 own pool' THEN w.residual ELSE 0 END)
             / nullif(sum(CASE WHEN o.opp_class = '1 own pool' THEN w.minutes_played ELSE 0 END), 0), 3)
                                              AS resid90_own_pool
FROM walk w
JOIN opposition o  ON o.run = w.run AND o.match_id = w.match_id AND o.player_id = w.player_id
JOIN club_league cl ON cl.club = w.own_club
WHERE w.run = '2 seeded' AND w.minutes_played > 0
GROUP BY 1 HAVING sum(w.minutes_played) > 300000
ORDER BY 4;

-- 3b restricted to the island column and computed for BOTH runs, so 3c and 3d
-- read the one table rather than each carrying its own copy of the expression.
CREATE TEMP TABLE league_deficit AS
SELECT w.run, cl.league,
       90 * sum(CASE WHEN o.opp_class = '2 other league' THEN w.residual ELSE 0 END)
         / nullif(sum(CASE WHEN o.opp_class = '2 other league' THEN w.minutes_played ELSE 0 END), 0)
           AS resid90
FROM walk w
JOIN opposition o   ON o.run = w.run AND o.match_id = w.match_id AND o.player_id = w.player_id
JOIN club_league cl ON cl.club = w.own_club
WHERE w.minutes_played > 0
GROUP BY 1, 2 HAVING sum(w.minutes_played) > 300000;

.print === 3c. the island test, unseeded vs seeded: how much of it item 16 took away ===
SELECT s.league, round(u.resid90, 3) AS unseeded, round(s.resid90, 3) AS seeded,
       round(s.resid90 - u.resid90, 3) AS moved
FROM league_deficit s JOIN league_deficit u ON u.league = s.league AND u.run = '1 unseeded'
WHERE s.run = '2 seeded' ORDER BY 3;

.print === 3d. the island stated as one number: the spread of 3b, both runs ===
SELECT run, count(*) AS leagues,
       round(min(resid90), 3) AS worst, round(max(resid90), 3) AS best,
       round(max(resid90) - min(resid90), 3) AS spread,
       round(stddev_samp(resid90), 4) AS sd
FROM league_deficit GROUP BY 1 ORDER BY 1;


-- ===========================================================================
-- 4. SCOTLAND, WHICH IS THE RECORDED EXAMPLE.
--
-- CONTEXT's Island entry names it, #39 measured the 227%, and #9 predicts that
-- stripping the minnow inflation may turn Scottish football into a NET LOSER of
-- rating mass with no calibration work done at all. This is that prediction,
-- checked. `net_excl_unpriced` is the quantity #39 reasoned about: what the
-- league gained that did NOT come off opposition with no league football.
-- ===========================================================================
.print === 4. per-league rating mass, and how much of it came off unpriced sides ===
-- `pct_of_net` reproduces #39's headline figure and is UNSTABLE BY
-- CONSTRUCTION: its denominator is a net gain that crosses zero, so a league
-- whose total sits near nothing reports a percentage in the hundreds or a
-- negative one. Read `net_excl_unpriced` for the actual quantity - what the
-- league gained that did not come off opposition with no league football.
CREATE TEMP TABLE mass AS
SELECT w.run, cl.league,
       idx_delta(sum(w.value_gained)) AS idx_pts_total,
       idx_delta(sum(CASE WHEN o.opp_class = '3 unpriced'
                          THEN w.value_gained ELSE 0 END)) AS idx_pts_from_unpriced,
       sum(w.minutes_played) AS minutes
FROM walk w
JOIN opposition o   ON o.run = w.run AND o.match_id = w.match_id AND o.player_id = w.player_id
JOIN club_league cl ON cl.club = w.own_club
GROUP BY 1, 2 HAVING sum(w.minutes_played) > 300000;

SELECT m.run, m.league,
       round(m.idx_pts_total, 1)          AS idx_pts_total,
       round(m.idx_pts_from_unpriced, 1)  AS from_unpriced,
       round(100.0 * m.idx_pts_from_unpriced / nullif(m.idx_pts_total, 0), 0) AS pct_of_net,
       round(m.idx_pts_total - m.idx_pts_from_unpriced, 1) AS net_excl_unpriced
FROM mass m ORDER BY m.league, m.run;


-- ===========================================================================
-- 5. COL1, THE OTHER ISLAND ITEM 16 NAMED.
--
-- 20 clubs, 200 matches, all against each other, and the vendor files the
-- competition with no type at all. #16 classified it as league football so its
-- clubs stopped reading as cup minnows; nothing has yet placed its LEVEL. If
-- the count here is near zero then nothing in this run ever can, and that is a
-- finding about pass 2 rather than about Colombia.
--
-- TWO THINGS THIS QUERY GOT WRONG AT FIRST, both recorded because they are the
-- easy mistakes to make twice.
--
--   It counted raw BRIDGES, which is the coarse measure section 3 exists to
--   reject - cup ties against unpriced clubs are bridges, and they place
--   nobody's level. Counting them read Scotland at 17.5% bridged and therefore
--   well connected, when its connection to priced football is 4.5%.
--
--   It required 500 matches, which excluded COL1 - the league the section is
--   named for, and at ~200 matches a season exactly the size that cannot clear
--   a bar set in matches. The floor is minutes now, matching sections 3 and 4,
--   and a league too small even for that is a finding rather than a row to hide.
-- ===========================================================================
.print === 5. the least-connected leagues: who cannot be placed at all ===
SELECT cl.league,
       count(DISTINCT w.match_id)                                    AS matches,
       count(DISTINCT CASE WHEN o.opp_class = '2 other league'
                           THEN w.match_id END)                      AS vs_other_leagues,
       round(100.0 * count(DISTINCT CASE WHEN o.opp_class = '2 other league'
                                         THEN w.match_id END)
-- Counted in MATCHES here and in minutes in 3b, so the two disagree on any one
-- league (Scotland 8.2% of matches, 4.5% of minutes) and neither is wrong.
             / count(DISTINCT w.match_id), 1)                        AS pct_of_matches,
       round(idx(avg(w.rating_before)), 1)                           AS mean_peak_index
FROM walk w
JOIN opposition o   ON o.run = w.run AND o.match_id = w.match_id AND o.player_id = w.player_id
JOIN club_league cl ON cl.club = w.own_club
WHERE w.run = '2 seeded'
GROUP BY 1 HAVING sum(w.minutes_played) > 300000
ORDER BY 4 ASC;


-- ===========================================================================
-- 6. SCOTT BROWN, RE-MEASURED.
--
-- CONTEXT's Island entry quotes him: Scotland gave him +122.9 index points and
-- the rest of his career took only 35.0 back, both measured unseeded. If the
-- seed alone moves this materially then the glossary's example needs its
-- numbers restated, which is a documentation consequence of item 16 that nobody
-- has checked. 12688 = Scott Brown, 28003 = Lionel Messi as the control.
--
-- THE SPLIT IS BY COUNTRY, not by competition, and that is what reproduces the
-- recorded pair: Brown's +122.9 is SC1 (95.6) plus the Scottish Cup (27.3), and
-- his -35.0 is the European nights. Grouping by league id alone puts the
-- domestic cup on the wrong side of the line and reports +60.3 / +16.5, which
-- is a different question wearing the same numbers' clothes. COL1 has no
-- competitions row at all (#16), so the country falls back to the id.
--
-- MESSI IS A WEAK CONTROL AND SHOULD BE READ AS ONE. His club league is Spain
-- by argmax, so his Ligue 1 and MLS football counts as "everywhere else" -
-- league football sitting in a bucket that for Brown holds only European
-- nights. The comparison that carries weight is Brown's own two rows.
-- ===========================================================================
.print === 6. Brown and Messi: index points gained at home and abroad ===
SELECT w.run, p.name,
       CASE WHEN coalesce(c.country_name, w.competition_id)
                 = coalesce(hc.country_name, cl.league) THEN '1 own country'
            ELSE '2 everywhere else' END                 AS where_earned,
       count(DISTINCT w.match_id)                        AS matches,
       round(sum(w.minutes_played), 0)                   AS minutes,
       round(idx_delta(sum(w.value_gained)), 1)          AS idx_pts_gained
FROM walk w
JOIN club_league cl        ON cl.club = w.own_club
JOIN tm.players p          ON p.player_id = w.player_id
LEFT JOIN tm.competitions c  ON c.competition_id = w.competition_id
LEFT JOIN tm.competitions hc ON hc.competition_id = cl.league
WHERE w.player_id IN (28003, 12688)
GROUP BY 1, 2, 3 ORDER BY 2, 1, 3;
