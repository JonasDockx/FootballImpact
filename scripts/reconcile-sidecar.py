#!/usr/bin/env python3
"""Grade the sidecar's reconstructions against a vendor team sheet.

Item 30 stage 2. Until 2026-07-30 the vendor had no team sheets before 2013, so
every released match in that era rests on a reconstruction -- most of them on
stage 4b-4's bulk rule, "a starter is a listed player never subbed on", derived
from the appearances roster and the substitution events. That rule was applied to
4,573 matches and there was never any way to check whether it was right.

Backfilling the season's real team sheets hands back ground truth for exactly
those matches, and this script is what reads the verdict. It is the reason stage 2
precedes stage 3: two hours of scraping says how far to trust the reconstruction
that a large part of the current spine rests on.

It REPORTS ONLY. ADR 0009's 2026-07-29 amendment is explicit that a refreshed
snapshot never overrides a release -- *Match state* says Released means "checked
and approved by hand", and discarding that silently is the permanent invisible
failure item 29 refused. Disagreement is evidence to act on one match at a time
through the editor.

    python3 scripts/reconcile-sidecar.py 2012 \
        --snapshot .../transfermarkt-datasets-2012.duckdb

Two readings need care, and both bit on the first run:

- **Squad membership will disagree badly and that is correct.** The sidecar knows
  only players the appearances record names, i.e. players who actually played; the
  vendor sheet also lists unused substitutes. In 2012 that gap was 39,464 rows and
  none of it reaches a rating, because an unused substitute plays no minutes. Read
  the starting-XI row, not the squad row.
- **A name can disagree while naming the same footballer.** Where the vendor's
  `players` table carried nobody, the editor minted a synthetic id (1000000000+,
  registered in sidecar.manual_players). Those are an id-identity artifact, not a
  lineup error, and the script counts them separately -- in 2012 they were 14 of
  the 17 disagreeing names, and treating them as errors made the reconstruction
  look five times worse than it is.
"""

import argparse
import collections
import csv
import pathlib
import sys

try:
    import duckdb
except ModuleNotFoundError:
    sys.exit("duckdb missing - run this under the vendor's Poetry venv interpreter")

FOOTBALL_DATA = pathlib.Path("C:/Users/dockx/Documents/Programmeren/FootballData")
if not FOOTBALL_DATA.is_dir():  # inside WSL the same directory is a mount
    FOOTBALL_DATA = pathlib.Path("/mnt/c/Users/dockx/Documents/Programmeren/FootballData")

# The editor mints ids above this line for players the vendor never named
# (item 26). They are real footballers under a local identity, so a comparison by
# id alone would score every one of them as a reconstruction error.
SYNTHETIC_ID_FLOOR = 1_000_000_000

LINEUP_TYPES = ("starting_lineup", "substitutes")


def lineups_by_game(connection, sql):
    """{game_id: {club_id: {type: {player_id, ...}}}} for one lineup source."""
    out = collections.defaultdict(
        lambda: collections.defaultdict(lambda: {t: set() for t in LINEUP_TYPES}))
    for game_id, club_id, player_id, lineup_type in connection.sql(sql).fetchall():
        if lineup_type in LINEUP_TYPES:
            out[game_id][club_id][lineup_type].add(player_id)
    return out


def flatten(clubs, *types):
    """Every player id in a match, across both clubs, for the given lineup types."""
    ids = set()
    for sides in clubs.values():
        for lineup_type in types:
            ids |= sides[lineup_type]
    return ids


def compare(season, snapshot, sidecar, out_csv):
    connection = duckdb.connect()
    connection.execute(f"ATTACH '{snapshot}' AS v (READ_ONLY)")
    connection.execute(f"ATTACH '{sidecar}' AS s (READ_ONLY)")

    # The reconciliation set: released matches for which the vendor now has a
    # sheet. A released match the vendor still cannot see is not gradeable and is
    # left out rather than counted as agreement.
    connection.execute(f"""
        CREATE TEMP TABLE pairs AS
        SELECT m.game_id,
               (m.provenance LIKE '%bulk reconstruction%') AS bulk
        FROM s.matches m
        JOIN v.games g ON CAST(g.game_id AS BIGINT) = m.game_id
        WHERE m.status = 'released'
          AND g.season = {season}
          AND EXISTS (SELECT 1 FROM v.game_lineups l
                      WHERE CAST(l.game_id AS BIGINT) = m.game_id)
    """)

    vendor = lineups_by_game(connection, """
        SELECT CAST(l.game_id AS BIGINT), CAST(l.club_id AS BIGINT),
               CAST(l.player_id AS BIGINT), l.type
        FROM v.game_lineups l
        WHERE CAST(l.game_id AS BIGINT) IN (SELECT game_id FROM pairs)
    """)
    side = lineups_by_game(connection, """
        SELECT game_id, club_id, player_id, type FROM s.game_lineups
        WHERE game_id IN (SELECT game_id FROM pairs)
    """)
    bulk_flag = dict(connection.sql("SELECT game_id, bulk FROM pairs").fetchall())

    rows = []
    for game_id in sorted(bulk_flag):
        v_clubs, s_clubs = vendor.get(game_id, {}), side.get(game_id, {})
        v_start = flatten(v_clubs, "starting_lineup")
        s_start = flatten(s_clubs, "starting_lineup")
        v_squad = flatten(v_clubs, *LINEUP_TYPES)
        s_squad = flatten(s_clubs, *LINEUP_TYPES)
        missing = s_start - v_start
        # Club-level, so a correct eleven attributed to the wrong side is caught.
        clubs_exact = all(
            v_clubs.get(club, {}).get("starting_lineup", set())
            == s_clubs.get(club, {}).get("starting_lineup", set())
            for club in set(v_clubs) | set(s_clubs))
        rows.append({
            "game_id": game_id,
            "bulk": int(bulk_flag[game_id]),
            "vendor_starters": len(v_start),
            "sidecar_starters": len(s_start),
            "starters_agree": len(v_start & s_start),
            "synthetic_id": len([p for p in missing if p >= SYNTHETIC_ID_FLOOR]),
            "real_disagreement": len([p for p in missing if p < SYNTHETIC_ID_FLOOR]),
            "promoted_sub": len(s_start & (v_squad - v_start)),
            "squad_agree": len(v_squad & s_squad),
            "vendor_squad": len(v_squad),
            "sidecar_squad": len(s_squad),
            "clubs_exact": int(clubs_exact),
        })
    connection.close()

    if not rows:
        sys.exit(f"no released season-{season} match has a vendor team sheet")

    with out_csv.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return rows


def report(label, subset):
    if not subset:
        return
    matches = len(subset)
    agree = sum(r["starters_agree"] for r in subset)
    vendor_starters = sum(r["vendor_starters"] for r in subset)
    perfect = sum(1 for r in subset
                  if r["clubs_exact"] and r["vendor_starters"] == 22
                  and r["starters_agree"] == 22)
    synthetic = sum(r["synthetic_id"] for r in subset)
    real = sum(r["real_disagreement"] for r in subset)
    squad = sum(r["squad_agree"] for r in subset)
    vendor_squad = sum(r["vendor_squad"] for r in subset)
    print(f"\n=== {label}: {matches:,} matches")
    print(f"  starters agreeing            {agree:,} of {vendor_starters:,}"
          f"  ({100 * agree / vendor_starters:.2f}%)")
    print(f"  all 22 right, right club     {perfect:,}"
          f"  ({100 * perfect / matches:.2f}%)")
    print(f"  disagreeing: real errors     {real:,}")
    print(f"  disagreeing: synthetic ids   {synthetic:,}  (same footballer, local id)")
    print(f"  squad membership agreeing    {squad:,} of {vendor_squad:,}"
          f"  ({100 * squad / vendor_squad:.2f}%)"
          f"  <- the gap is unused substitutes; reaches no rating")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("season", help="season label, e.g. 2012")
    parser.add_argument("--snapshot",
                        default=str(FOOTBALL_DATA / "transfermarkt-datasets.duckdb"),
                        help="the rebuilt snapshot carrying the new team sheets")
    parser.add_argument("--sidecar",
                        default=str(FOOTBALL_DATA / "transfermarkt-sidecar.duckdb"))
    parser.add_argument("--out", default=None,
                        help="per-match CSV (default FootballData/reconciliation-<season>.csv)")
    args = parser.parse_args()

    out_csv = pathlib.Path(args.out) if args.out else (
        FOOTBALL_DATA / f"reconciliation-{args.season}.csv")
    rows = compare(args.season, args.snapshot, args.sidecar, out_csv)

    print(f"reconciliation set: {len(rows):,} released season-{args.season} "
          f"matches with a vendor team sheet")
    print(f"per-match detail -> {out_csv}")
    report("ALL", rows)
    report("bulk reconstruction (stage 4b-4)", [r for r in rows if r["bulk"]])
    report("earlier releases", [r for r in rows if not r["bulk"]])


if __name__ == "__main__":
    main()
