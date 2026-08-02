#!/usr/bin/env python3
"""Item 30, decision 5a: predict the census from the raw scraper tree, BEFORE the
rebuild, so a wrong rebuild cannot be mistaken for a good one.

A `dbt build` on this pipeline takes tens of minutes and rewrites twelve tables.
Once it has run, "93,166 games" is just a number on a screen -- there is nothing
to check it against, and every stage of item 30 is a widening, so "bigger than
last time" is not evidence. This script says what the numbers MUST be, from the
files the build is about to read, using the vendor's own de-duplication rules.
Predict first, build, then compare.

It re-implements three vendor rules and nothing else:

  base_games        row_number() over (partition by game_id order by season desc)
                    keeps ONE record per game_id -- the newest season directory
                    wins. A game scraped under two season labels is one game.
                    Then `where "date" is not null`: a fixture Transfermarkt
                    lists without a date is dropped outright. Rare and easy to
                    miss -- it was the whole of stage 3's 17-game overshoot, all
                    of them in 2020, where postponements went up undated.
  base_game_lineups the same partition, then UNION (not UNION ALL) across the
                    four blocks, so exact duplicate player rows collapse.
  game_lineups      INNER JOIN base_games: a team sheet whose fixture is not in
                    any `games` file reaches no curated row at all.

`game_events` is predicted too, and the reason is a trap worth naming: it is NOT
its own raw asset. Goals, cards and substitutions are carried inside each record
of the `games` file, and base_game_events unnests `$.events` from exactly the
same deduped rows base_games keeps. So a stage that scrapes only `games` and
`game_lineups` still moves three curated tables, not two -- and stage 3's build
duly tripped the vendor's own row-count bound on game_events.

What it deliberately does NOT predict: appearances, players, clubs and
competitions. Those four hang off raw assets no stage-3 pass touches, so they
must come back UNCHANGED, and "unchanged" needs no arithmetic -- it needs the
old file, which scripts/compare-snapshots.sql already attaches.

Usage, from the vendor repo, under its Poetry interpreter (duckdb is not
installed system-wide):

  cd ~/spine/transfermarkt-datasets
  ~/.cache/pypoetry/virtualenvs/transfermarkt-datasets-*/bin/python \
      /mnt/c/.../GoalImpact/scripts/census-predict.py \
      --raw data/raw/transfermarkt-scraper \
      --against /mnt/c/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets-2012.duckdb

`--against` is optional and names the PREVIOUS snapshot; given one, every
predicted figure is printed beside where it stands today and the delta is the
thing to sanity-check against the scrape logs.

Reading the raw tree costs a few minutes and about 4 GB -- the same JSON parse
the build is about to do, minus the writes.
"""

import argparse
import os

import duckdb

# Both raw assets are read straight from the .json.gz tree, exactly as the
# vendor's sources.yml does -- and the "exactly" matters. It reads each line as
# one opaque VARCHAR through read_csv with no delimiter and no quoting, then
# parses it with json() downstream. Reading the same files with read_json
# instead looks equivalent and is not: DuckDB then infers a schema, `value` is
# not a key any record has, and every row comes back NULL. That mistake predicts
# a census of one game, which is at least loud rather than plausible.
READ = ("read_csv('{path}', header=False, "
        "columns=struct_pack(value := 'VARCHAR'), "
        "delim='', quote='', strict_mode=false, filename=True)")
RAW_GAMES = "{raw}/*/games.json.gz"
RAW_LINEUPS = "{raw}/*/game_lineups.json.gz"

# Season is element 5 of the path when it is split on '/', counting from 1, for
# the vendor's own relative path `data/raw/transfermarkt-scraper/2019/games...`.
# An absolute or otherwise-shaped path would land on a different element, so we
# take the second-to-last component instead, which is the season under any root.
SEASON = "str_split(filename, '/')[len(str_split(filename, '/')) - 1]"


def build_views(con, raw):
    con.execute(f"""
        CREATE OR REPLACE VIEW raw_games AS
        SELECT json(value) AS json_row,
               {SEASON} AS season,
               str_split(json_extract_string(json(value), '$.href'), '/')[5] AS game_id
        FROM {READ.format(path=RAW_GAMES.format(raw=raw))}
    """)
    con.execute(f"""
        CREATE OR REPLACE VIEW raw_lineups AS
        SELECT json(value) AS json_row,
               {SEASON} AS season,
               json_extract_string(json(value), '$.game_id') AS game_id
        FROM {READ.format(path=RAW_LINEUPS.format(raw=raw))}
    """)
    # One record per game_id, newest season directory winning -- base_games,
    # base_game_events and base_game_lineups all open with this, and it is the
    # rule most likely to make a naive count too big.
    con.execute("""
        CREATE OR REPLACE VIEW games_deduped_all AS
        SELECT game_id, season, json_row FROM (
            SELECT game_id, season, json_row,
                   row_number() over (partition by game_id order by season desc) AS n
            FROM raw_games) WHERE n = 1
    """)
    # base_games' date, verbatim, only so its `is not null` can be applied. The
    # vendor's raw data switched from m/d/y to d/m/y in 2024, and tournament
    # games are always d/m/y whatever the season -- hence the two orderings.
    # base_game_events does NOT apply this filter, which is why the two views
    # are separate.
    con.execute("""
        CREATE OR REPLACE VIEW games_deduped AS
        SELECT game_id, season FROM games_deduped_all
        WHERE (CASE
            WHEN json_extract_string(json_row, '$.date') != 'null' THEN
              CASE WHEN season::integer >= 2024
                     OR json_extract_string(json_row, '$.parent.href') LIKE '%/saison_id/%'
                   THEN coalesce(
                          try_strptime(json_extract_string(json_row, '$.date'), '%a, %d/%m/%y'),
                          try_strptime(json_extract_string(json_row, '$.date'), '%a, %m/%d/%y'))
                   ELSE coalesce(
                          try_strptime(json_extract_string(json_row, '$.date'), '%a, %m/%d/%y'),
                          try_strptime(json_extract_string(json_row, '$.date'), '%a, %d/%m/%y'))
              END
        END) IS NOT NULL
    """)
    con.execute("""
        CREATE OR REPLACE VIEW lineups_deduped AS
        SELECT game_id, json_row FROM (
            SELECT game_id, json_row,
                   row_number() over (partition by game_id order by season desc) AS n
            FROM raw_lineups) WHERE n = 1
    """)


def block(side, kind):
    return f"""
        SELECT game_id,
               (str_split(json_extract_string(json_row, '$.{side}.href'), '/')[5])::integer AS club_id,
               '{kind}' AS "type",
               regexp_replace(j ->> 'number', '\\s+', '', 'g') AS "number",
               (str_split((j ->> 'href'), '/')[5])::integer AS player_id,
               (j ->> 'name') AS player_name,
               (j ->> 'team_captain')::integer AS team_captain,
               (j ->> 'position') AS "position"
        FROM (SELECT game_id, json_row,
                     unnest(json_transform(json_extract(json_row, '$.{side}.{kind}'), '["JSON"]')) AS j
              FROM lineups_deduped)
    """


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="data/raw/transfermarkt-scraper")
    ap.add_argument("--against", default=None, help="previous snapshot .duckdb")
    ap.add_argument("--memory-limit", default="16GB")
    args = ap.parse_args()

    con = duckdb.connect()
    con.execute(f"SET memory_limit='{args.memory_limit}'")
    # Same reason as the dbt profile: the JSON parse holds memory DuckDB cannot
    # spill, and one worker per core exhausted 18.7 GB during stage 0.
    con.execute("SET threads=4")
    con.execute("SET preserve_insertion_order=false")

    print(f"--> reading {os.path.abspath(args.raw)}")
    build_views(con, args.raw)

    seasons = con.execute(
        "SELECT season, count(*) FROM raw_games GROUP BY 1 ORDER BY 1"
    ).fetchall()
    print(f"    {len(seasons)} season directories: "
          + ", ".join(f"{s}({n:,})" for s, n in seasons))

    print("\n--> predicting `games`")
    games = con.execute("SELECT count(*) FROM games_deduped").fetchone()[0]

    print("--> predicting `game_lineups` (the expensive one)")
    con.execute(f"""
        CREATE OR REPLACE TABLE lineup_rows AS
        {block('home_club', 'starting_lineup')}
        UNION {block('home_club', 'substitutes')}
        UNION {block('away_club', 'starting_lineup')}
        UNION {block('away_club', 'substitutes')}
    """)
    # The inner join in curated/game_lineups.sql: a sheet without a fixture is
    # invisible downstream, so it must not be counted here either.
    lineups = con.execute("""
        SELECT count(*) FROM lineup_rows INNER JOIN games_deduped USING (game_id)
    """).fetchone()[0]
    sheeted = con.execute("""
        SELECT count(DISTINCT game_id) FROM lineup_rows
        WHERE game_id IN (SELECT game_id FROM games_deduped)
    """).fetchone()[0]
    orphans = con.execute("""
        SELECT count(DISTINCT game_id) FROM lineup_rows
        WHERE game_id NOT IN (SELECT game_id FROM games_deduped)
    """).fetchone()[0]

    # base_game_events, which lives inside the `games` file rather than beside
    # it. `select distinct` over exactly these nine columns, and no date filter.
    print("--> predicting `game_events`")
    events = con.execute("""
        SELECT count(*) FROM (
            SELECT DISTINCT
                game_id,
                (j -> 'minute')::integer,
                (j ->> 'type'),
                str_split(j -> 'club' ->> 'href', '/')[5]::integer,
                (j -> 'club' ->> 'name'),
                str_split(j -> 'player' ->> 'href', '/')[5]::integer,
                (j -> 'action' ->> 'description'),
                str_split(j -> 'action' -> 'player_in' ->> 'href', '/')[5]::integer,
                str_split(j -> 'action' -> 'player_assist' ->> 'href', '/')[5]::integer
            FROM (SELECT game_id,
                         unnest(json_transform(json_extract(json_row, '$.events'),
                                               '["JSON"]')) AS j
                  FROM games_deduped_all))
    """).fetchone()[0]

    print("\n--> predicting per-season coverage")
    per_season = con.execute("""
        SELECT g.season,
               count(*) AS games,
               count(*) FILTER (WHERE l.game_id IS NOT NULL) AS with_sheet
        FROM games_deduped g
        LEFT JOIN (SELECT DISTINCT game_id FROM lineup_rows) l USING (game_id)
        GROUP BY 1 ORDER BY 1
    """).fetchall()

    old = None
    if args.against:
        o = duckdb.connect(args.against, read_only=True)
        old = {
            "games": o.execute("SELECT count(*) FROM games").fetchone()[0],
            "game_lineups": o.execute("SELECT count(*) FROM game_lineups").fetchone()[0],
            "game_events": o.execute("SELECT count(*) FROM game_events").fetchone()[0],
            "per_season": dict(o.execute("""
                SELECT season::varchar, count(DISTINCT g.game_id)
                FROM games g
                WHERE EXISTS (SELECT 1 FROM game_lineups l WHERE l.game_id = g.game_id)
                GROUP BY 1
            """).fetchall()),
        }
        o.close()

    print("\n=== PREDICTED CENSUS ===")
    hdr = f"{'quantity':<34}{'now':>14}{'predicted':>14}{'delta':>12}"
    print(hdr if old else f"{'quantity':<34}{'predicted':>14}")
    print("-" * len(hdr if old else f"{'quantity':<34}{'predicted':>14}"))
    for key, name, pred in (("games", "games", games),
                            ("game_lineups", "game_lineups rows", lineups),
                            ("game_events", "game_events rows", events)):
        if old:
            print(f"{name:<34}{old[key]:>14,}{pred:>14,}{pred - old[key]:>+12,}")
        else:
            print(f"{name:<34}{pred:>14,}")
    print(f"\ngames carrying a team sheet:      {sheeted:,}")
    if orphans:
        print(f"team sheets with NO fixture row (dropped by the inner join): {orphans:,}")

    print("\n=== per season: games, and how many carry a sheet ===")
    print(f"{'season':<10}{'games':>10}{'with sheet':>13}{'now':>10}{'delta':>10}")
    for season, n, ws in per_season:
        if old:
            was = old["per_season"].get(str(season), 0)
            print(f"{season:<10}{n:>10,}{ws:>13,}{was:>10,}{ws - was:>+10,}")
        else:
            print(f"{season:<10}{n:>10,}{ws:>13,}")

    print("\nThe four untouched tables -- appearances, players, clubs and competitions --")
    print("must come back UNCHANGED. compare-snapshots.sql checks that.")


if __name__ == "__main__":
    main()
