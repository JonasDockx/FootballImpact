#!/usr/bin/env python3
"""Apply item 30's politeness commitment to the vendor's scraper.

ADR 0009 (amended 2026-07-29) pins acquisition at ONE REQUEST PER SECOND with a
concurrency of one. The vendor's `transfermarkt-scraper` ships with no rate
limiting of any kind -- no delay, no concurrency cap -- and Crawlee's default is
to autoscale to whatever the machine sustains, which on 32 cores means it hits
Transfermarkt as hard as it can. Transfermarkt's robots.txt disallows bots, so
this project's position (item 15, restated in item 30 decision 4) is personal
use, slow rate, cache everything. The throttle is what makes that defensible.

This lives in GoalImpact rather than as an edit to the vendor clone on purpose:
the clone is disposable and an edit in it is invisible to everyone and lost on
the next `poetry update`. Checked in here, the commitment is a fact in the
repository and can be re-applied in one command. RE-RUN THIS AFTER ANY VENDOR
UPDATE -- poetry installs the scraper from git, so an update silently restores
the unthrottled original.

    python3 scripts/throttle-scraper.py --check    # is it applied?
    python3 scripts/throttle-scraper.py            # apply (idempotent)

Run it inside WSL, from anywhere, with the vendor venv discoverable; it edits
the INSTALLED tfmkt/common.py in the Poetry virtualenv.
"""

import argparse
import pathlib
import subprocess
import sys

MARKER = "# --- GoalImpact item 30: politeness throttle (ADR 0009) ---"

ORIGINAL = """    failures = []
    crawler = ParselCrawler()
"""

THROTTLED = f"""    failures = []
    {MARKER}
    # One request per second, one at a time. Not a tuning knob: ADR 0009 pins
    # it. max_tasks_per_minute is the rate; the three concurrency numbers must
    # ALL be 1, because desired_concurrency alone still lets the autoscaler
    # climb to max_concurrency (default 100) whenever the machine looks idle,
    # which is exactly what a 32-core desktop always looks like.
    crawler = ParselCrawler(
        concurrency_settings=ConcurrencySettings(
            min_concurrency=1,
            max_concurrency=1,
            desired_concurrency=1,
            max_tasks_per_minute=60,
        )
    )
"""

IMPORT_FROM = "from crawlee import Request\n"
IMPORT_TO = "from crawlee import ConcurrencySettings, Request\n"


def find_common_py() -> pathlib.Path:
    """Locate the installed tfmkt/common.py inside the vendor's Poetry venv."""
    repo = pathlib.Path.home() / "spine" / "transfermarkt-datasets"
    if not repo.is_dir():
        sys.exit(f"vendor repo not found at {repo}")
    venv = subprocess.check_output(
        ["poetry", "env", "info", "--path"], cwd=repo, text=True
    ).strip()
    hits = list(pathlib.Path(venv).glob("lib/python*/site-packages/tfmkt/common.py"))
    if not hits:
        sys.exit(f"tfmkt/common.py not found under {venv} - is the scraper installed?")
    return hits[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="report whether the throttle is applied, change nothing")
    args = parser.parse_args()

    target = find_common_py()
    source = target.read_text()
    applied = MARKER in source

    if args.check:
        print(f"{target}\n  throttle: {'APPLIED' if applied else 'NOT APPLIED'}")
        sys.exit(0 if applied else 1)

    if applied:
        print(f"already applied: {target}")
        return

    if ORIGINAL not in source:
        sys.exit(
            f"cannot patch {target}: the code this expects to replace is not there.\n"
            "The vendor has changed create_crawler(). Re-read it and update this "
            "script rather than scraping unthrottled."
        )

    patched = source.replace(IMPORT_FROM, IMPORT_TO, 1).replace(ORIGINAL, THROTTLED, 1)
    target.write_text(patched)
    print(f"throttle applied: {target}")
    print("  1 request/second, concurrency 1")


if __name__ == "__main__":
    main()
