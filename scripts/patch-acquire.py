#!/usr/bin/env python3
"""Patch the vendor's acquire script: one data bug, one data-loss bug.

Companion to throttle-scraper.py, which patches the installed `tfmkt` package.
This one patches the acquire script in the transfermarkt-datasets WORKING TREE,
so a `git checkout` or `git pull` in that clone reverts it. RE-RUN AFTER EITHER.

    python3 scripts/patch-acquire.py --check
    python3 scripts/patch-acquire.py

Both patches were written after item 30 stage 1 crashed on 2026-07-29, having
already spent 3h42m scraping 11,567 player profiles - all of which were then
deleted by the vendor's own error handler.

PATCH 1 - social_media breaks the merge.
    _duckdb.ConversionException: Conversion Error: Malformed JSON at byte 0 of
    input: unexpected character.  Input: "http://instagram.com/ivakhnov_7"
The players crawler emits `social_media` as a LIST of URLs. Where the existing
raw file has that column all-null, DuckDB infers it as JSON; the new scrape has
it as VARCHAR[]; UNION ALL BY NAME then tries to read a bare URL as JSON and
fails. The vendor's VARCHAR_CASTS/JSON_CASTS tables exist for exactly this
family of problem but do not list this column - presumably because their own
runs never scraped a player who had filled it in.

`to_json` is the right normaliser (the column is a list, not a scalar), and it
is safe beyond doubt: `social_media` is read by NO dbt model - verified with
git grep across dbt/ and transfermarkt_datasets/ - so nothing downstream can
observe the representation change.

PATCH 2 - a failed merge deletes the scrape.
The vendor scrapes to a temp file, merges, and on ANY exception unlinks the temp
file before re-raising. So a bug in the merge - a step that takes seconds and
touches no network - destroys hours of rate-limited scraping that had already
succeeded. At one request per second this is the most expensive failure mode in
the whole pipeline, and stage 3 is ~50 hours of it.

After this patch the file is kept and its path logged, and
scripts/merge-scrape.py can complete the merge without re-scraping.
"""

import argparse
import pathlib
import sys

REPO = pathlib.Path.home() / "spine" / "transfermarkt-datasets"
TARGET = REPO / "scripts" / "acquiring" / "transfermarkt-scraper.py"

MARKER = "# --- GoalImpact item 30 ---"

JSON_CASTS_FROM = """JSON_CASTS = {
  'players': ['parent'],"""

JSON_CASTS_TO = f"""JSON_CASTS = {{
  # {MARKER} social_media is a LIST of URLs and breaks UNION ALL BY NAME when
  # the existing file has it all-null (inferred JSON) and the new scrape has it
  # populated (VARCHAR[]). Read by no dbt model, so normalising is free.
  'players': ['parent', 'social_media'],"""

KEEP_FROM = """  except Exception:
    if os.path.exists(tmp_path):
      os.unlink(tmp_path)
    raise
"""

KEEP_TO = f"""  except Exception:
    # {MARKER} do NOT delete the scrape when the merge fails. The scrape is
    # hours of rate-limited network work; the merge is seconds of local SQL.
    # Recover with: python3 scripts/merge-scrape.py <asset> <path>
    if os.path.exists(tmp_path):
      logging.error(
        f"MERGE FAILED - keeping scrape output at {{tmp_path}} "
        f"({{os.path.getsize(tmp_path)}} bytes). Do not delete it; "
        f"recover with scripts/merge-scrape.py rather than re-scraping."
      )
    raise
"""

# PATCH 3 - appearances.result is a scoreline, not JSON.
#     _duckdb.ConversionException: Malformed JSON at byte 1 of input:
#     unexpected content after document.  Input: "0:1"
# Same family as social_media, second column, found the same way - by crashing
# after the scrape had already succeeded. `result` is f"{goals}:{opponent}" in
# the crawler, so it is a plain VARCHAR wherever it is populated and JSON-typed
# wherever the existing file has it all-null.
RESULT_FROM = """VARCHAR_CASTS = {
  'clubs': ['coach_name', 'coach_href', 'total_market_value', 'league_position'],"""

RESULT_TO = f"""VARCHAR_CASTS = {{
  # {MARKER} result is a scoreline string ("0:1"), read as JSON when the
  # existing file has the column all-null.
  'appearances': ['result'],
  'clubs': ['coach_name', 'coach_href', 'total_market_value', 'league_position'],"""

# PATCH 4 - scrapes must not be preserved into /tmp.
# Patch 2 kept the scrape on merge failure and logged its path, and it was still
# lost: WSL shuts an idle VM down and brings it back with an empty /tmp, which is
# what happened to 278,804 appearance rows overnight on 2026-07-30. Preserving to
# a directory under $HOME makes patch 2 actually mean something.
TMPDIR_FROM = """  with tempfile.NamedTemporaryFile(suffix='.jsonl.gz', delete=False) as tmp:
    tmp_path = tmp.name
"""

TMPDIR_TO = f"""  # {MARKER} not /tmp: WSL wipes it when the idle VM restarts, which lost a
  # preserved scrape once already. $HOME survives.
  _scrape_dir = os.path.join(os.path.expanduser('~'), 'spine', 'scrapes')
  os.makedirs(_scrape_dir, exist_ok=True)
  with tempfile.NamedTemporaryFile(suffix='.jsonl.gz', delete=False, dir=_scrape_dir) as tmp:
    tmp_path = tmp.name
"""

PATCHES = [
    ("social_media cast", JSON_CASTS_FROM, JSON_CASTS_TO),
    ("keep scrape on merge failure", KEEP_FROM, KEEP_TO),
    ("appearances.result cast", RESULT_FROM, RESULT_TO),
    ("scrapes outside /tmp", TMPDIR_FROM, TMPDIR_TO),
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    if not TARGET.exists():
        sys.exit(f"vendor acquire script not found at {TARGET}")

    source = TARGET.read_text()
    applied = source.count(MARKER)

    if args.check:
        print(f"{TARGET}\n  patches applied: {applied} of {len(PATCHES)}")
        sys.exit(0 if applied == len(PATCHES) else 1)

    if applied == len(PATCHES):
        print(f"already applied: {TARGET}")
        return

    for name, old, new in PATCHES:
        if new in source:
            print(f"  already applied: {name}")
            continue
        if old not in source:
            sys.exit(
                f"cannot apply '{name}': the code it expects is not there.\n"
                "The vendor has changed this script. Re-read it and update this "
                "patch rather than running without it."
            )
        source = source.replace(old, new, 1)
        print(f"  applied: {name}")

    TARGET.write_text(source)
    print(f"patched: {TARGET}")


if __name__ == "__main__":
    main()