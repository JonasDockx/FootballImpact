#!/usr/bin/env python3
"""Recover a scrape whose merge failed, without re-scraping.

Item 30. The vendor's acquire script scrapes to a temp file and then merges it
into data/raw/transfermarkt-scraper/<season>/<asset>.json.gz. The scrape is
hours of rate-limited network work; the merge is seconds of local SQL. When the
merge fails, patch-acquire.py keeps the temp file instead of deleting it, and
this script finishes the job.

    python3 scripts/merge-scrape.py players 2023 /tmp/tmpwmllgbje.jsonl.gz

It reuses the vendor's own merge_output(), so the result is identical to what a
successful run would have produced - this is a resumption, not a reimplementation.
Fix the cause of the failure first (usually a column needing a cast in
VARCHAR_CASTS or JSON_CASTS), then run this.
"""

import argparse
import os
import pathlib
import subprocess
import sys

REPO = pathlib.Path.home() / "spine" / "transfermarkt-datasets"
sys.path.insert(0, str(REPO))
sys.path.insert(0, str(REPO / "scripts" / "acquiring"))


def reexec_in_venv() -> None:
    """Re-run this script under the vendor's venv interpreter if duckdb is absent.

    The vendor's acquire module imports duckdb at module scope, and duckdb lives
    only in the Poetry venv - so invoking this with the system python3 fails with
    ModuleNotFoundError *after* the scrape has already succeeded. That is exactly
    the shape of failure this script exists to recover from, so it must not be
    the shape of failure this script CAUSES: it cost the games merge on
    2026-07-30 and would have cost game_lineups three hours later.

    Rather than rely on every caller remembering `poetry run`, find the
    interpreter and start again under it.
    """
    try:
        import duckdb  # noqa: F401
        return
    except ModuleNotFoundError:
        pass

    if os.environ.get("_MERGE_SCRAPE_REEXEC"):
        sys.exit("duckdb missing even under the venv interpreter - is the venv intact?")

    try:
        venv = subprocess.check_output(
            ["poetry", "env", "info", "--path"], cwd=REPO, text=True,
            env={**os.environ, "PATH": f"{pathlib.Path.home()}/.local/bin:{os.environ['PATH']}"},
        ).strip()
    except Exception as exc:
        sys.exit(f"duckdb missing and could not locate the venv: {exc}")

    python = pathlib.Path(venv) / "bin" / "python"
    if not python.exists():
        sys.exit(f"duckdb missing and no interpreter at {python}")

    # flush=True is load-bearing: execve replaces the process image, and a
    # block-buffered stdout (which is what a pipe or a log redirect gives you)
    # is discarded with it. Without this the re-exec happens silently and the
    # log gives no sign it ever did - which briefly looked like the system
    # python having duckdb after all.
    print(f"re-executing under {python}", flush=True)
    os.execve(str(python), [str(python), __file__, *sys.argv[1:]],
              {**os.environ, "_MERGE_SCRAPE_REEXEC": "1"})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("asset", help="asset name, e.g. players")
    parser.add_argument("season", help="season, e.g. 2023")
    parser.add_argument("scrape", help="path to the preserved scrape output (.jsonl.gz)")
    args = parser.parse_args()

    # Before anything else: this must run under an interpreter that has duckdb.
    reexec_in_venv()

    # Resolve before the chdir below, so a relative path on the command line
    # still means what the caller meant.
    scrape = pathlib.Path(args.scrape).resolve()
    if not scrape.exists():
        sys.exit(f"scrape file not found: {scrape}")

    # The vendor's read_config() opens 'config.yml' relative to the working
    # directory, so the merge only works when run from inside the repo. Every
    # manual merge so far happened to be, and backfill.sh was not - which cost
    # season 2022's merge on 2026-07-30 after both its scrapes had finished.
    # Being run from the wrong directory is precisely the kind of avoidable
    # failure this script exists to absorb, so it fixes its own cwd rather than
    # relying on callers.
    os.chdir(REPO)

    target = REPO / "data" / "raw" / "transfermarkt-scraper" / args.season / f"{args.asset}.json.gz"
    if not target.parent.is_dir():
        sys.exit(f"no such season directory: {target.parent}")

    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "vendor_acquire", REPO / "scripts" / "acquiring" / "transfermarkt-scraper.py")
    vendor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vendor)

    before = target.stat().st_size if target.exists() else 0
    print(f"merging {scrape} ({scrape.stat().st_size:,} bytes)")
    print(f"     -> {target} ({before:,} bytes)")

    total = vendor.merge_output(target, scrape, args.asset)

    print(f"merged: {total:,} total records, {target.stat().st_size:,} bytes")
    print("the scrape file is left in place; delete it once you are satisfied.")


if __name__ == "__main__":
    main()
