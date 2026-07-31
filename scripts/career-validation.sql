-- Item 28 / #32: the career validation pass, run against the results file.
--
--   duckdb -readonly < scripts/career-validation.sql
--
-- Reads nothing but the three ADR 0009 files, all read-only, and writes
-- nothing: every table below is a temp table in the session. Re-run it after
-- any designated run to re-derive the numbers recorded on item 28.
--
-- The two subjects: 28003 = Lionel Messi, 12688 = Scott Brown (the Celtic
-- captain; Transfermarkt carries three Scott Browns and the other two are a
-- goalkeeper and a St. Johnstone midfielder).
--
-- THE SNAPSHOT MUST BE THE ONE THE RESULTS FILE WAS RUN AGAINST. It is joined
-- to for fixtures and lineups, so a mismatch silently drops matches rather than
-- failing. The results file present on 2026-07-31 holds a run over the 2012
-- backfill (item 30 stage 3), hence -2012 below; point this at
-- DataFiles.SNAPSHOT again when the backfill lands there.

ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb'          AS res (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets-2012.duckdb' AS tm  (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb'   AS sc  (READ_ONLY);

-- Only matches that actually moved a rating. A Held match is in the vendor
-- snapshot and in no history row, so joining through the history is what keeps
-- "the data the model saw" and "the data the vendor has" apart.
CREATE TEMP TABLE replayed AS SELECT DISTINCT match_id FROM res.rating_history;

-- Sidecar releases replace the vendor's copy wholesale (CONTEXT 'Sidecar'), so
-- the fixture and the lineup both prefer it. Without this, the ten 2018/19
-- Barcelona matches repaired by hand drop out of Messi's walk.
CREATE TEMP TABLE fixture AS
SELECT r.match_id,
       coalesce(s.competition_id,   CAST(g.competition_id AS VARCHAR)) AS competition_id,
       coalesce(s.competition_type, g.competition_type)                AS competition_type,
       coalesce(s.season,           g.season)                          AS season,
       coalesce(s.home_club_id,     CAST(g.home_club_id AS BIGINT))    AS home_club_id,
       coalesce(s.away_club_id,     CAST(g.away_club_id AS BIGINT))    AS away_club_id,
       coalesce(s.home_club_name,   g.home_club_name)                  AS home_club_name,
       coalesce(s.away_club_name,   g.away_club_name)                  AS away_club_name
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

-- Item 16's diagnostic, made a set: the clubs that DO play league football in
-- the replayed data. Everyone else is cup opposition the model prices at 0,
-- which it reads as exactly average rather than unknown.
CREATE TEMP TABLE league_clubs AS
SELECT DISTINCT club FROM (
  SELECT home_club_id AS club FROM fixture WHERE competition_type = 'domestic_league'
  UNION ALL
  SELECT away_club_id        FROM fixture WHERE competition_type = 'domestic_league');

-- One row per player per match: the walk. rating_after - rating_before is the
-- exact value this match moved him, and it sums to his final Value - which
-- residual does not, because the update factor shrinks with exposure.
CREATE TEMP TABLE walk AS
SELECT h.player_id, h.match_id, h.match_date, f.season, f.competition_id, f.competition_type,
       l.club_id AS own_club,
       CASE WHEN l.club_id = f.home_club_id THEN f.away_club_id   ELSE f.home_club_id   END AS opp_club,
       CASE WHEN l.club_id = f.home_club_id THEN f.away_club_name ELSE f.home_club_name END AS opp_name,
       (l.club_id NOT IN (SELECT club FROM league_clubs)) AS own_no_league,
       (CASE WHEN l.club_id = f.home_club_id THEN f.away_club_id ELSE f.home_club_id END
          NOT IN (SELECT club FROM league_clubs)) AS opp_no_league,
       h.minutes_before, h.rating_before, h.residual, h.minutes_played, h.rating_after,
       h.rating_after - h.rating_before AS value_gained
FROM res.rating_history h
JOIN fixture f ON f.match_id = h.match_id
LEFT JOIN lineup l ON l.match_id = h.match_id AND l.player_id = h.player_id;

-- Strength as the model saw it: one lineup's mean pre-match rating.
CREATE TEMP TABLE club_strength AS
SELECT match_id, own_club AS club_id, avg(rating_before) AS mean_rating FROM walk GROUP BY 1, 2;

-- ADR 0011's pinned scale, written once so no query below re-derives it.
CREATE TEMP MACRO idx(v) AS 100 + 20 * (v - 1.8374) / 7.1729;

-- 1. Where each career ended, and where it sits.
SELECT p.name, count(*) AS matches, round(sum(w.minutes_played), 0) AS minutes,
       round(sum(w.residual), 1) AS residual,
       round(max_by(w.rating_after, w.match_date), 2) AS final_value,
       round(idx(max_by(w.rating_after, w.match_date)), 1) AS final_index
FROM walk w JOIN tm.players p ON p.player_id = w.player_id
WHERE w.player_id IN (28003, 12688) GROUP BY 1;

-- 2. The walk, by season.
SELECT w.player_id, w.season, count(*) AS matches, round(sum(w.minutes_played), 0) AS minutes,
       round(sum(w.residual), 1) AS residual,
       round(idx(max_by(w.rating_after, w.match_date)), 1) AS index_at_season_end,
       round(20 * sum(w.value_gained) / 7.1729, 1) AS index_points_gained
FROM walk w WHERE w.player_id IN (28003, 12688) GROUP BY 1, 2 ORDER BY 1, 2;

-- 3. The walk, by competition: which competitions built the rating.
SELECT w.player_id, w.competition_id, w.competition_type, count(*) AS matches,
       round(sum(w.minutes_played), 0) AS minutes,
       round(20 * sum(w.value_gained) / 7.1729, 1) AS index_points_gained,
       round(90 * sum(w.residual) / sum(w.minutes_played), 3) AS residual_per_90
FROM walk w WHERE w.player_id IN (28003, 12688) GROUP BY 1, 2, 3 ORDER BY 1, 6 DESC;

-- 4. Item 16's number: what came from opposition with no league football.
SELECT w.player_id, w.opp_no_league, count(*) AS matches,
       round(sum(w.minutes_played), 0) AS minutes,
       round(20 * sum(w.value_gained) / 7.1729, 1) AS index_points_gained,
       round(90 * sum(w.residual) / sum(w.minutes_played), 3) AS residual_per_90
FROM walk w WHERE w.player_id IN (28003, 12688) GROUP BY 1, 2 ORDER BY 1, 2;

-- ...and the same split over the whole population, which is the baseline the
-- two careers have to be read against.
SELECT opp_no_league, round(sum(minutes_played), 0) AS minutes,
       round(90 * sum(residual) / sum(minutes_played), 3) AS residual_per_90
FROM walk GROUP BY 1;

-- 5. ADR 0011's off-by-default first-2,000-minute drop, as a measurement:
--    how much of the career was banked while the model was still discovering
--    the player.
SELECT player_id,
       CASE WHEN minutes_before < 1000 THEN 'a 0-1000'
            WHEN minutes_before < 2000 THEN 'b 1000-2000'
            WHEN minutes_before < 4000 THEN 'c 2000-4000'
            WHEN minutes_before < 10000 THEN 'd 4000-10000'
            ELSE 'e 10000+' END AS phase,
       count(*) AS matches, round(sum(minutes_played), 0) AS minutes,
       round(20 * sum(value_gained) / 7.1729, 1) AS index_points_gained
FROM walk WHERE player_id IN (28003, 12688) GROUP BY 1, 2 ORDER BY 1, 2;

-- 6. Item 9's island, seen from the pitch: who each man actually played.
SELECT w.player_id, count(*) AS matches,
       round(idx(avg(own.mean_rating)), 1) AS mean_own_index,
       round(idx(avg(opp.mean_rating)), 1) AS mean_opponent_index
FROM walk w
JOIN club_strength own ON own.match_id = w.match_id AND own.club_id = w.own_club
JOIN club_strength opp ON opp.match_id = w.match_id AND opp.club_id = w.opp_club
WHERE w.player_id IN (28003, 12688) GROUP BY 1;

-- ...and where every league floats. A 15-point band across every top division
-- on earth is the island, stated as a number.
SELECT f.competition_id, count(DISTINCT f.match_id) AS matches,
       round(idx(avg(cs.mean_rating)), 1) AS mean_club_index
FROM fixture f JOIN club_strength cs ON cs.match_id = f.match_id
WHERE f.competition_type = 'domestic_league'
GROUP BY 1 HAVING count(DISTINCT f.match_id) > 500 ORDER BY 3 DESC;

-- 7. The club-level control, which is what makes it a property of Scotland
--    rather than of Scott Brown: 371 = Celtic, 131 = FC Barcelona.
SELECT w.own_club,
       CASE WHEN w.competition_type = 'domestic_league' THEN '1 domestic league'
            WHEN w.competition_id IN ('CL','EL','CLQ','ELQ','ECL','ECLQ','UCOL','SUC') THEN '2 europe'
            ELSE '3 other' END AS bucket,
       count(DISTINCT w.match_id) AS matches, round(sum(w.minutes_played), 0) AS minutes,
       round(90 * sum(w.residual) / sum(w.minutes_played), 3) AS residual_per_90
FROM walk w WHERE w.own_club IN (371, 131) GROUP BY 1, 2 ORDER BY 1, 2;
