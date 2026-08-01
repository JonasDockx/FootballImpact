-- Item 16: deriving the unpriced-club seed, and the bias statistics that
-- describe it.
--
--   duckdb < scripts/unpriced-seed.sql
--
-- The constant this file produces is MEASURED, not tuned (#39). It is read off
-- the residual gap unpriced sides actually run, inverted through the link
-- function into rating points, and then pinned and dated in Main beside
-- BASE_RATE and h. A grid sweep around it is a check on this arithmetic, never
-- the source of the value.
--
-- THE SNAPSHOT MUST BE THE ONE THE RESULTS FILE WAS RUN AGAINST - it is joined
-- to for fixtures and lineups, so a mismatch silently drops matches rather than
-- failing. Sibling of scripts/model-bias-diagnostics.sql, whose prelude this
-- repeats rather than imports (DuckDB has no include). Two differences from it,
-- both deliberate:
--
--   * it attaches transfermarkt-datasets-2012.duckdb and this attaches
--     transfermarkt-datasets.duckdb, which is DataFiles.SNAPSHOT. Enough of
--     item 30 stage 3 has been merged into DataFiles.SNAPSHOT for it to carry
--     88,958 games back to 2012, which is why this reads it - but STAGE 3 IS
--     STILL RUNNING (2015 lineups in flight on 2026-08-01, seasons 2022 down
--     to 2012, and #30 has further scraping after that). So this file is a
--     MOVING population, not a finished one: re-running this script on a later
--     snapshot will not reproduce the numbers below, and by ADR 0014 rule 3
--     that is correct behaviour rather than a bug.
--   * league_clubs below matches ClubPools.java and that file's does not; see
--     the note on it. TransfermarktLoader.LEAGUE_COMPETITIONS is the authority
--     for that list and this is a copy of it - there is no way to share a
--     constant across Java and SQL, so a change there needs a change here.
--
-- IT MATTERS WHETHER THE RUN IT READS HAD THE SEED ON. The derivation is only
-- a derivation against an UNSEEDED run: query 3 measures the gap that is still
-- there, so against a seeded results file it reports the gap the seed did not
-- close, not the constant. The recorded values, both on 85,050 matches:
--
--   seed 0.00 (the derivation)  shortfall 0.3568  ->  2.581 rating points
--   seed 2.58 (what shipped)    shortfall 0.2931  ->  2.122 rating points
--
-- The second is a MEASUREMENT OF THE RESIDUE, not a correction to the first,
-- and the residue is large: seeding at 2.58 closed only about a fifth of the
-- gap. That is expected and it is item 16's built-in ceiling - the seed prices
-- a player once, on his debut, and 41.3% of appearances at unpriced clubs are
-- not debuts, while a seeded debutant starts climbing back from his first
-- minute. The rest of the gap is a level problem, which is item 9. Do NOT read
-- 2.122 as a re-derivation and re-pin the constant to it: that would be
-- chasing a moving target of the model's own making.
--
-- EVERY NUMBER BELOW BELONGS TO A POPULATION (#39): the run this reads is
-- 85,050 matches, 2012-07-09 to 2026-07-06, at k 0.10 / K0 1.0 / H 4000 /
-- floor 0.05 / h 2.0. Re-running it after the spine moves will not reproduce
-- the recorded figures - it will produce that run's figures, which is the point.

ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb'     AS res (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb' AS tm  (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb'  AS sc  (READ_ONLY);

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

-- League football, as ClubPools.java reads it, and NOT as
-- model-bias-diagnostics.sql does. Two corrections, both found while building
-- the seam on 2026-08-01 and both changing an answer:
--
--   COL1 / EJPL / POBE / BPO4 are league football that games.competition_type
--     does not say so for - the Colombian first division has no competitions
--     row at all, and the three Belgian play-offs are filed "other". Without
--     this, all 20 Colombian clubs read as cup minnows when they are an island.
--   A national side plays no league either, and is nobody's minnow. Excluded
--     by kind, not by league.
CREATE TEMP TABLE league_clubs AS
SELECT DISTINCT club FROM (
  SELECT home_club_id AS club FROM fixture
   WHERE competition_type = 'domestic_league'
      OR competition_id IN ('COL1', 'EJPL', 'POBE', 'BPO4')
  UNION ALL
  SELECT away_club_id        FROM fixture
   WHERE competition_type = 'domestic_league'
      OR competition_id IN ('COL1', 'EJPL', 'POBE', 'BPO4'));

CREATE TEMP TABLE national_teams AS
SELECT DISTINCT club FROM (
  SELECT home_club_id AS club FROM fixture WHERE competition_type = 'national_team_competition'
  UNION ALL
  SELECT away_club_id        FROM fixture WHERE competition_type = 'national_team_competition');

CREATE TEMP TABLE unpriced AS
SELECT DISTINCT club FROM (
  SELECT home_club_id AS club FROM fixture UNION ALL SELECT away_club_id FROM fixture)
WHERE club NOT IN (SELECT club FROM league_clubs)
  AND club NOT IN (SELECT club FROM national_teams);

CREATE TEMP TABLE walk AS
SELECT h.player_id, h.match_id, f.competition_id,
       l.club_id AS own_club,
       (CASE WHEN l.club_id = f.home_club_id THEN f.away_club_id ELSE f.home_club_id END
          IN (SELECT club FROM unpriced)) AS opp_unpriced,
       (l.club_id IN (SELECT club FROM unpriced))                      AS own_unpriced,
       h.residual, h.minutes_played
FROM res.rating_history h
JOIN fixture f ON f.match_id = h.match_id
LEFT JOIN lineup l ON l.match_id = h.match_id AND l.player_id = h.player_id;


-- ===========================================================================
-- 1. THE GAP. Residual per 90 earned against unpriced opposition, against
--    everyone else. The difference is goals per 90 of expectation that the
--    unpriced side fails to meet - unearned by whoever is playing them.
SELECT opp_unpriced,
       count(*)                                      AS player_matches,
       round(sum(minutes_played))                    AS minutes,
       round(sum(residual), 1)                       AS residual_total,
       round(90 * sum(residual) / sum(minutes_played), 4) AS residual_per_90
FROM walk
WHERE minutes_played > 0 AND own_club IS NOT NULL
GROUP BY 1 ORDER BY 1;

-- 2. THE SAME GAP, one number.
SELECT round(
         90 * sum(CASE WHEN opp_unpriced THEN residual END)
            / sum(CASE WHEN opp_unpriced THEN minutes_played END)
       - 90 * sum(CASE WHEN NOT opp_unpriced THEN residual END)
            / sum(CASE WHEN NOT opp_unpriced THEN minutes_played END), 4) AS gap_goals_per_90
FROM walk WHERE minutes_played > 0 AND own_club IS NOT NULL;

-- 3. THE SEED, inverted through the link function (ADR 0007). Expected goal
--    difference over 90 minutes at a strength gap d is
--        90 * base * (e^(k d / 2) - e^(-k d / 2)) = 180 * base * sinh(k d / 2)
--    so the gap that would have PREDICTED the shortfall is
--        d = (2 / k) * asinh(shortfall / (180 * base))
--    at this spine's pinned base rate 0.01532 and link gain 0.10.
WITH g AS (
  SELECT 90 * sum(CASE WHEN opp_unpriced THEN residual END)
            / sum(CASE WHEN opp_unpriced THEN minutes_played END)
       - 90 * sum(CASE WHEN NOT opp_unpriced THEN residual END)
            / sum(CASE WHEN NOT opp_unpriced THEN minutes_played END) AS shortfall
  FROM walk WHERE minutes_played > 0 AND own_club IS NOT NULL)
SELECT round(shortfall, 4) AS shortfall_per_90,
       round((2 / 0.10) * asinh(shortfall / (180 * 0.01532)), 3) AS seed_rating_points
FROM g;

-- 4. HOW MUCH OF THE RUN THIS IS. Reconciles with Main's own printout, which
--    counts the same clubs off the fixture list rather than off the history.
SELECT (SELECT count(*) FROM unpriced)                                   AS unpriced_clubs,
       -- National sides out of the denominator as well as the numerator, so
       -- this reads 2,867 and not 2,952 - the same count ClubPools.clubs()
       -- makes, which is the point of printing it.
       (SELECT count(*) FROM (SELECT DISTINCT club FROM (
          SELECT home_club_id AS club FROM fixture
          UNION ALL SELECT away_club_id FROM fixture)
        WHERE club NOT IN (SELECT club FROM national_teams)))            AS clubs,
       round(100.0 * sum(CASE WHEN own_unpriced THEN 1 ELSE 0 END) / count(*), 1)
                                                                         AS pct_appearances_unpriced
FROM walk WHERE own_club IS NOT NULL;

-- 5. WHO IS ACTUALLY SEEDED. The mechanism prices a player once, on his first
--    appearance, so what it can reach is the DEBUT appearances at unpriced
--    clubs - not every appearance at one. A small share here means the seed
--    under-corrects the gap above by construction, whatever the constant is.
CREATE TEMP TABLE debut AS
SELECT player_id, min(match_id) AS first_match FROM walk GROUP BY 1;

SELECT sum(CASE WHEN w.own_unpriced THEN 1 ELSE 0 END)                   AS debuts_at_unpriced_clubs,
       count(*)                                                          AS debuts,
       round(100.0 * sum(CASE WHEN w.own_unpriced THEN 1 ELSE 0 END) / count(*), 1)
                                                                         AS pct
FROM debut d JOIN walk w ON w.player_id = d.player_id AND w.match_id = d.first_match
WHERE w.own_club IS NOT NULL;
