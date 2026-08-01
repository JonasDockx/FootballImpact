-- Item 39: the model-bias sequencing grill, run against the results file.
--
--   duckdb < scripts/model-bias-diagnostics.sql
--
-- Every number recorded on #39, #16, #12 and #13 on 2026-07-31 comes from here.
-- Reads the ADR 0009 files read-only and writes nothing; every table is a temp
-- table in the session. Sibling of scripts/career-validation.sql, whose prelude
-- (fixture / lineup / league_clubs / walk / club_strength / idx) this file
-- repeats rather than imports, because DuckDB has no include and a diagnostic
-- that cannot be run in one command is a diagnostic nobody runs.
--
-- THE SNAPSHOT MUST BE THE ONE THE RESULTS FILE WAS RUN AGAINST - it is joined
-- to for fixtures and lineups, so a mismatch silently drops matches rather than
-- failing. The results file present on 2026-07-31 holds a run over the 2012
-- backfill (item 30 stage 3), hence -2012 below; point this at
-- DataFiles.SNAPSHOT again when the backfill lands there.
--
-- EVERY NUMBER BELOW BELONGS TO A POPULATION (#39): the run this reads is
-- 85,050 matches, 2012-07-09 to 2026-07-06. Item 30 stage 3 is mid-flight, so
-- re-running this after the spine moves will not reproduce the recorded
-- figures - it will produce that run's figures, which is the point.
--
-- AND THE MODEL HAS MOVED TOO, not only the spine (2026-08-01). The results
-- file now holds a run with item 16's unpriced-club seed ON (run id ends
-- `-s2.58`), where every figure recorded here was measured with it off. The
-- item 16 section below is the one that changes most, and by design: the gap
-- it measures is what the seed was derived from, and re-reading it on a seeded
-- run measures the RESIDUE - 0.2931 goals/90 rather than 0.3568. Check the
-- run_id before trusting a comparison:
--     SELECT DISTINCT run_id FROM res.rating_history;
-- scripts/unpriced-seed.sql carries the full before/after and the derivation.

ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb'          AS res (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets-2012.duckdb' AS tm  (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb'       AS sc  (READ_ONLY);

CREATE TEMP TABLE replayed AS SELECT DISTINCT match_id FROM res.rating_history;

CREATE TEMP TABLE fixture AS
SELECT r.match_id,
       coalesce(s.competition_id,   CAST(g.competition_id AS VARCHAR)) AS competition_id,
       coalesce(s.competition_type, g.competition_type)                AS competition_type,
       coalesce(s.home_club_id,     CAST(g.home_club_id AS BIGINT))    AS home_club_id,
       coalesce(s.away_club_id,     CAST(g.away_club_id AS BIGINT))    AS away_club_id
FROM replayed r
LEFT JOIN tm.games   g ON CAST(g.game_id AS BIGINT) = r.match_id
LEFT JOIN sc.matches s ON s.game_id = r.match_id;

CREATE TEMP TABLE lineup AS
SELECT match_id, player_id, any_value(club_id) AS club_id FROM (
  SELECT r.match_id, CAST(l.player_id AS BIGINT) AS player_id, CAST(l.club_id AS BIGINT) AS club_id
  FROM sc.game_lineups l JOIN replayed r ON r.match_id = l.game_id
  UNION ALL
  SELECT r.match_id, CAST(l.player_id AS BIGINT), CAST(l.club_id AS BIGINT)
  FROM tm.game_lineups l JOIN replayed r ON r.match_id = l.game_id
  WHERE NOT EXISTS (SELECT 1 FROM sc.matches s WHERE s.game_id = l.game_id)
) GROUP BY 1, 2;

-- The clubs that DO play league football in the replayed data. Everyone else is
-- the cup opposition item 16 is about, priced at 0 - which this model reads as
-- exactly average rather than unknown.
CREATE TEMP TABLE league_clubs AS
SELECT DISTINCT club FROM (
  SELECT home_club_id AS club FROM fixture WHERE competition_type = 'domestic_league'
  UNION ALL
  SELECT away_club_id        FROM fixture WHERE competition_type = 'domestic_league');

CREATE TEMP TABLE walk AS
SELECT h.player_id, h.match_id, f.competition_id, f.competition_type,
       l.club_id AS own_club,
       (CASE WHEN l.club_id = f.home_club_id THEN f.away_club_id ELSE f.home_club_id END
          NOT IN (SELECT club FROM league_clubs)) AS opp_no_league,
       h.rating_before, h.residual, h.minutes_played,
       h.rating_after - h.rating_before AS value_gained
FROM res.rating_history h
JOIN fixture f ON f.match_id = h.match_id
LEFT JOIN lineup l ON l.match_id = h.match_id AND l.player_id = h.player_id;

CREATE TEMP TABLE club_strength AS
SELECT match_id, own_club AS club_id, avg(rating_before) AS mean_rating FROM walk GROUP BY 1, 2;

-- ADR 0011's pinned scale. Measured on the 2013 population, NOT on the run this
-- file reads (#32) - the ruler must not change length, so it stays put.
CREATE TEMP MACRO idx(v) AS 100 + 20 * (v - 1.8374) / 7.1729;


-- ===========================================================================
-- 1. ITEM 16, per league: who actually eats the cup-minnow inflation.
--
-- The number that reversed #32's ordering. Scotland is NOT unusually exposed
-- (5.2% of minutes, level with Spain and England) - but Scottish football's
-- whole net gain is SMALLER than what its clubs took off unpriced opposition
-- (227%), where Spain sits at 75% and England at 63%. Strip item 16 and the
-- Scottish league becomes a net loser of rating mass with no calibration work
-- done at all, which is why item 16 lands before item 9.
-- ===========================================================================
CREATE TEMP TABLE club_league AS
SELECT club, arg_max(comp, n) AS league FROM (
  SELECT club, competition_id AS comp, count(*) AS n FROM (
    SELECT home_club_id AS club, competition_id FROM fixture WHERE competition_type='domestic_league'
    UNION ALL
    SELECT away_club_id, competition_id FROM fixture WHERE competition_type='domestic_league')
  GROUP BY 1,2) GROUP BY 1;

.print === item 16: per-league inflow from opposition with no league football ===
SELECT cl.league,
       round(sum(w.minutes_played),0) AS all_minutes,
       round(100.0*sum(CASE WHEN w.opp_no_league THEN w.minutes_played ELSE 0 END)
             /sum(w.minutes_played),1) AS pct_min_vs_unpriced,
       round(20*sum(CASE WHEN w.opp_no_league THEN w.value_gained ELSE 0 END)/7.1729,1) AS idx_pts_from_unpriced,
       round(20*sum(w.value_gained)/7.1729,1) AS idx_pts_total,
       round(90*sum(CASE WHEN w.opp_no_league THEN w.residual ELSE 0 END)
             /nullif(sum(CASE WHEN w.opp_no_league THEN w.minutes_played ELSE 0 END),0),3) AS resid90_vs_unpriced
FROM walk w JOIN club_league cl ON cl.club = w.own_club
GROUP BY 1 HAVING sum(w.minutes_played) > 300000
ORDER BY 3 DESC;


-- ===========================================================================
-- 2. ITEM 13, the bound that closed it: how much football is played a man down.
--
-- 9,512 red cards over 8,684 of 85,050 matches (10.2%), but only 278,626
-- team-minutes short - ~2.79M player-minutes, 1.65% of the population's 169M.
-- At the half-a-goal-per-25-minutes figure item 13 quotes that is a career bias
-- around 0.003 goals/90, against item 16's measured 0.308. Note this sample is
-- ample: item 13 closes for the SIZE of the bias, not for inability to measure.
-- ===========================================================================
.print === item 13: red cards and man-down exposure ===
SELECT count(*) AS red_cards,
       count(DISTINCT e.game_id) AS matches_with_red,
       (SELECT count(*) FROM replayed) AS replayed_matches,
       round(90 - avg(e.minute), 1) AS avg_minutes_short_after_card,
       round(sum(90 - e.minute), 0) AS total_man_down_team_minutes
FROM tm.game_events e JOIN replayed r ON r.match_id = CAST(e.game_id AS BIGINT)
WHERE lower(e.type) LIKE '%card%' AND lower(e.description) LIKE '%red%';


-- ===========================================================================
-- 3. ITEM 12, the refutation: the scoreline states, and who scores in them.
--
-- Item 12 claims "a side 3-0 up eases off... the model expects a leading team to
-- keep scoring at full rate". Only 5.2% of playing time is 3+ apart, and in it
-- the LEADER scores 2.443/90 against the trailer's 1.147 - the opposite sign.
-- Query 3c holds the strength gap to rule out "the leader is simply better".
-- ===========================================================================
CREATE TEMP TABLE goal AS
SELECT CAST(e.game_id AS BIGINT) AS mid, e.minute,
       CASE WHEN e.club_id = gm.home_club_id THEN 1 ELSE -1 END AS sgn
FROM tm.game_events e
JOIN replayed r ON r.match_id = CAST(e.game_id AS BIGINT)
JOIN tm.games gm ON CAST(gm.game_id AS BIGINT) = CAST(e.game_id AS BIGINT)
WHERE e.type = 'Goals' AND e.minute BETWEEN 0 AND 90;

-- The state a goal was scored IN, so the goal is attributed to the scoreline
-- that preceded it rather than to the one it created.
CREATE TEMP TABLE scored AS
SELECT mid, minute, sgn,
       coalesce(sum(sgn) OVER (PARTITION BY mid ORDER BY minute
                               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS gd_before
FROM goal;

-- How long each scoreline state lasted. Time before the first goal is absent by
-- construction and is 0-apart: the 'a' row below understates by that amount,
-- which is why the recorded share of 0-1 apart is 83.5% and not 44.4%.
CREATE TEMP TABLE spans AS
SELECT mid, minute AS from_min,
       coalesce(lead(minute) OVER (PARTITION BY mid ORDER BY minute), 90) AS to_min,
       sum(sgn) OVER (PARTITION BY mid ORDER BY minute ROWS UNBOUNDED PRECEDING) AS gd
FROM goal;

CREATE TEMP MACRO state(gd) AS
  CASE WHEN abs(gd) >= 3 THEN 'c 3+ apart' WHEN abs(gd) = 2 THEN 'b 2 apart' ELSE 'a 0-1 apart' END;

.print === item 12a: share of playing time by scoreline state ===
SELECT state(gd) AS scoreline, sum(to_min - from_min) AS minutes,
       round(100.0 * sum(to_min - from_min) / (SELECT 90.0*count(*) FROM replayed), 1)
         AS pct_of_all_match_minutes
FROM spans GROUP BY 1 ORDER BY 1;

.print === item 12b: goals per 90 team-minutes, leader vs trailer (all matches) ===
WITH mins AS (SELECT state(gd) AS s, sum(to_min - from_min) AS m FROM spans GROUP BY 1),
gl AS (SELECT state(gd_before) AS s,
              sum(CASE WHEN gd_before <> 0 AND sign(sgn) =  sign(gd_before) THEN 1 ELSE 0 END) AS by_leader,
              sum(CASE WHEN gd_before <> 0 AND sign(sgn) <> sign(gd_before) THEN 1 ELSE 0 END) AS by_trailer
       FROM scored GROUP BY 1)
SELECT gl.s AS scoreline, mins.m AS minutes,
       round(90.0*gl.by_leader /mins.m, 3) AS leader_per_90,
       round(90.0*gl.by_trailer/mins.m, 3) AS trailer_per_90
FROM gl JOIN mins USING (s) ORDER BY 1;

-- The control: sides the MODEL rated within 0.5 rating points of each other at
-- kickoff, which removes "the leader outscores the trailer because the leader is
-- a better team". The sign does not change. One honest caveat survives it -
-- conditioning on a scoreline that REACHED 3+ selects matches where something
-- unusual happened, and no strength control removes that selection.
--
-- Expect the third decimal to move between runs of this query and not of 1 or 2:
-- `lineup` dedupes with any_value(), which is nondeterministic where a player
-- has two club rows in one match, and that shifts a handful of matches across
-- the gap < 0.5 boundary. Recorded as 2.282 / 1.334 on item 12 and reproducing
-- at 2.277 / 1.331. Nothing that matters is at that decimal.
CREATE TEMP TABLE gap AS
SELECT match_id, max(mean_rating) - min(mean_rating) AS gap
FROM club_strength GROUP BY 1 HAVING count(*) = 2;

.print === item 12c: the same, evenly matched only (model gap < 0.5 points) ===
WITH ev AS (SELECT match_id FROM gap WHERE gap < 0.5),
mins AS (SELECT state(gd) AS s, sum(to_min - from_min) AS m
         FROM spans JOIN ev ON ev.match_id = spans.mid GROUP BY 1),
gl AS (SELECT state(gd_before) AS s,
              sum(CASE WHEN gd_before <> 0 AND sign(sgn) =  sign(gd_before) THEN 1 ELSE 0 END) AS by_leader,
              sum(CASE WHEN gd_before <> 0 AND sign(sgn) <> sign(gd_before) THEN 1 ELSE 0 END) AS by_trailer
       FROM scored JOIN ev ON ev.match_id = scored.mid GROUP BY 1)
SELECT gl.s AS scoreline, mins.m AS minutes,
       round(90.0*gl.by_leader /mins.m, 3) AS leader_per_90,
       round(90.0*gl.by_trailer/mins.m, 3) AS trailer_per_90,
       round(90.0*(gl.by_leader+gl.by_trailer)/mins.m, 3) AS combined_per_90
FROM gl JOIN mins USING (s) ORDER BY 1;
