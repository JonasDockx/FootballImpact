-- Item 30: THE reproduction gate. This is the one that decides.
--
-- Every stage from item 18 to item 29 gated on an identical goalimpact.csv, by
-- md5. That gate is retired here, and not by choice: the vendor's dbt pipeline
-- is non-deterministic for display labels (see scripts/compare-snapshots.sql for
-- the measurement), so the club name attached to a player churns between builds
-- while his rating does not. An md5 over the whole CSV therefore fails for ever,
-- on nothing, and a gate that always fails is a gate nobody reads.
--
-- What replaces it is stricter where it counts and silent where it does not:
--
--   check 1  no rating and no minute total moved, anywhere        MUST be 0
--   check 2  no player's own row moved                            MUST be 0
--   check 3  club labels churned                                  reported only
--
-- Check 1 is the real statement - the population of (minutes, rating) pairs is
-- unchanged. Check 2 adds that they are attached to the same players. Together
-- they say "the same footballers earned the same ratings over the same minutes",
-- which is everything the old md5 said minus the part that was never stable.
--
-- Usage: run Main against the candidate snapshot, then
--   duckdb -init scripts/compare-ratings.sql -c ".quit"
-- with BASELINE pointing at the CSV the run must reproduce.

.mode box

CREATE TEMP TABLE base AS SELECT * FROM read_csv_auto(
  'c:/Users/dockx/Documents/Programmeren/GoalImpact/goalimpact-baseline.csv');
CREATE TEMP TABLE reb AS SELECT * FROM read_csv_auto(
  'c:/Users/dockx/Documents/Programmeren/GoalImpact/goalimpact.csv');

.print "=== check 1 (DECIDES): did any rating or minute total move? ==="
SELECT
  (SELECT count(*) FROM (SELECT minutes, rating, goalkeeper FROM base
                         EXCEPT ALL SELECT minutes, rating, goalkeeper FROM reb)) AS only_in_baseline,
  (SELECT count(*) FROM (SELECT minutes, rating, goalkeeper FROM reb
                         EXCEPT ALL SELECT minutes, rating, goalkeeper FROM base)) AS only_in_candidate;

.print ""
.print "=== check 2 (DECIDES): same players, same numbers? ==="
SELECT
  (SELECT count(*) FROM (SELECT player, minutes, rating, goalkeeper FROM base
                         EXCEPT ALL SELECT player, minutes, rating, goalkeeper FROM reb)) AS only_in_baseline,
  (SELECT count(*) FROM (SELECT player, minutes, rating, goalkeeper FROM reb
                         EXCEPT ALL SELECT player, minutes, rating, goalkeeper FROM base)) AS only_in_candidate;

.print ""
.print "=== check 3 (reported, never fails): club labels that churned ==="
SELECT count(*) AS team_relabelled
FROM base b JOIN reb r ON r.player = b.player AND r.minutes = b.minutes AND r.rating = b.rating
WHERE b.team IS DISTINCT FROM r.team;

.print ""
.print "=== a sample of the churn, to confirm it is spelling and not substance ==="
SELECT b.player, b.team AS baseline_team, r.team AS candidate_team
FROM base b JOIN reb r ON r.player = b.player AND r.minutes = b.minutes AND r.rating = b.rating
WHERE b.team IS DISTINCT FROM r.team
LIMIT 10;
