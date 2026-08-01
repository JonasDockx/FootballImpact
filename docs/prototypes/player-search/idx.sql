ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb' AS r (READ_ONLY);
ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb' AS tm (READ_ONLY);

CREATE OR REPLACE TEMP TABLE career AS
  SELECT player_id, match_id, match_date, rating_after,
         100 + 20*(rating_after - 1.8374)/7.1729 AS idx,
         sum(minutes_played) OVER (PARTITION BY player_id ORDER BY match_date, match_id) AS cum_min
  FROM r.rating_history;

CREATE OR REPLACE TEMP TABLE tot AS
  SELECT player_id, sum(minutes_played) AS mins, count(*) AS apps,
         min(match_date) AS first_d, max(match_date) AS last_d
  FROM r.rating_history GROUP BY player_id;

CREATE OR REPLACE TEMP TABLE nm AS
  SELECT player_id, any_value(player_name) AS nm FROM tm.game_lineups WHERE player_name IS NOT NULL GROUP BY player_id;

-- club of last appearance
CREATE OR REPLACE TEMP TABLE lastclub AS
  SELECT gl.player_id, any_value(c.name ORDER BY gl.date DESC) AS club
  FROM tm.game_lineups gl JOIN tm.clubs c ON c.club_id = gl.club_id GROUP BY gl.player_id;

CREATE OR REPLACE TEMP TABLE idx AS
SELECT t.player_id AS id, nm.nm AS name, strip_accents(nm.nm) AS ascii,
       lc.club, p.position, p.date_of_birth::date AS dob,
       t.mins::int AS mins, t.apps,
       year(t.first_d) AS y0, year(t.last_d) AS y1,
       round((SELECT max(idx) FROM career c WHERE c.player_id=t.player_id AND c.cum_min>=1000),1) AS peak,
       round((SELECT idx FROM career c WHERE c.player_id=t.player_id ORDER BY match_date DESC, match_id DESC LIMIT 1),1) AS latest
FROM tot t JOIN nm USING(player_id)
     LEFT JOIN lastclub lc ON lc.player_id=t.player_id
     LEFT JOIN tm.players p ON p.player_id=t.player_id
WHERE t.mins >= 1000;

COPY (SELECT * FROM idx ORDER BY peak DESC NULLS LAST) TO 'index.json' (FORMAT JSON, ARRAY true);
SELECT count(*) AS eligible_indexed, count(club) AS with_club, count(position) AS with_position, count(dob) AS with_dob FROM idx;
