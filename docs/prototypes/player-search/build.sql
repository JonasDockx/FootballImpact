.read idx.sql
CREATE OR REPLACE TEMP TABLE elig AS SELECT * FROM career WHERE cum_min>=1000 AND player_id IN (SELECT id FROM idx);
CREATE OR REPLACE TEMP TABLE monthly AS
  SELECT player_id, (year(match_date)-2012)*12 + month(match_date)-1 AS m, round(last(idx ORDER BY match_date, match_id),1) AS v
  FROM elig GROUP BY 1,2;
CREATE OR REPLACE TEMP TABLE series AS
  SELECT player_id, list(v ORDER BY m) AS vs, list(m ORDER BY m) AS ms FROM monthly GROUP BY player_id;

COPY (
  SELECT i.id, i.name, coalesce(i.club,'') AS club, coalesce(i.position,'') AS pos,
         i.mins, i.y0, i.y1, i.peak, s.ms, s.vs,
         -- date of birth as a month index on the same m=0 is 2012-01 axis, so the
         -- age axis is (m - dobm)/12. Negative for anyone born before 2012, which
         -- is everybody. NULL for the 11.5% with no players row.
         CASE WHEN i.dob IS NOT NULL
              THEN (year(i.dob)-2012)*12 + month(i.dob)-1 END AS dobm
  FROM idx i JOIN series s ON s.player_id=i.id
  ORDER BY i.peak DESC NULLS LAST
) TO 'proto-data.json' (FORMAT JSON, ARRAY true);
