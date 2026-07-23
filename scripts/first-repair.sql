-- First sidecar repair (item 26, stage 3; ADR 0009). Run ONCE against an
-- absent sidecar to create it:
--   duckdb "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-sidecar.duckdb" ".read scripts/first-repair.sql"
-- The default database is the sidecar being created; the vendor snapshot is
-- attached read-only and every row copied from it. Game 2501210 (Olympiakos
-- Volos 0-1 Panathinaikos, Greek Cup 2014) was Held for "no starting
-- goalkeeper": the vendor tagged goalkeeper Luke Steele (3539) as
-- Centre-Forward. The only change from the vendor is that one cell.

ATTACH 'C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb' AS vendor (READ_ONLY);

CREATE TABLE matches (
    game_id           BIGINT,
    status            VARCHAR,
    date              DATE,
    competition_id    VARCHAR,
    season            VARCHAR,
    round             VARCHAR,
    competition_type  VARCHAR,
    home_club_id      BIGINT,
    home_club_name    VARCHAR,
    away_club_id      BIGINT,
    away_club_name    VARCHAR,
    home_club_goals   INTEGER,
    away_club_goals   INTEGER,
    provenance        VARCHAR,
    commit_hash       VARCHAR
);

CREATE TABLE game_lineups (
    game_id      BIGINT,
    club_id      BIGINT,
    player_id    BIGINT,
    player_name  VARCHAR,
    position     VARCHAR,
    "type"       VARCHAR
);

CREATE TABLE game_events (
    game_id       BIGINT,
    minute        INTEGER,
    "type"        VARCHAR,
    club_id       BIGINT,
    player_id     BIGINT,
    player_in_id  BIGINT,
    description   VARCHAR
);

CREATE TABLE appearances (
    game_id         BIGINT,
    minutes_played  INTEGER
);

INSERT INTO matches
SELECT CAST(g.game_id AS BIGINT), 'released', g.date, g.competition_id, g.season,
       g.round, g.competition_type,
       g.home_club_id, g.home_club_name, g.away_club_id, g.away_club_name,
       g.home_club_goals, g.away_club_goals,
       'First repair (item 26 stage 3): vendor tagged goalkeeper Luke Steele (3539) '
       || 'as Centre-Forward, leaving Panathinaikos with no starting keeper; retagged '
       || 'to Goalkeeper. Derived from players.position; 2026-07-23.',
       (SELECT commit_hash FROM vendor.version)
FROM vendor.games g
WHERE CAST(g.game_id AS BIGINT) = 2501210;

INSERT INTO game_lineups
SELECT CAST(game_id AS BIGINT), club_id, player_id, player_name,
       CASE WHEN player_id = 3539 THEN 'Goalkeeper' ELSE position END,
       type
FROM vendor.game_lineups
WHERE game_id = 2501210;

INSERT INTO game_events
SELECT CAST(game_id AS BIGINT), minute, type, club_id, player_id, player_in_id, description
FROM vendor.game_events
WHERE CAST(game_id AS BIGINT) = 2501210;

INSERT INTO appearances
SELECT game_id, minutes_played
FROM vendor.appearances
WHERE game_id = 2501210;
