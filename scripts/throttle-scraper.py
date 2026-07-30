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
import re
import subprocess
import sys

MARKER = "# --- GoalImpact item 30: politeness throttle (ADR 0009) ---"

# Patching create_crawler() is NOT enough, learned the hard way on 2026-07-30:
# five crawlers never call it and construct their own, so they ran unthrottled.
# `appearances` sent ~11,600 requests in 5m44s - about 34 per second - straight
# through the commitment ADR 0009 pins. The timed test that "verified" the
# throttle had exercised `clubs`, which does use create_crawler(), so it proved
# the rule only for the crawlers that already obeyed it.
#
# These are the offenders, by the crawler class they build directly:
SELF_BUILDING = {
    "appearances.py": "HttpCrawler",
    "countries.py": "ParselCrawler",
    "national_teams.py": "ParselCrawler",
    "tournament_editions.py": "ParselCrawler",
}
# confederations.py is deliberately NOT here: it makes no HTTP request at all,
# just prints five hardcoded hrefs. It appeared in the first draft of this list
# because the survey that produced the list inferred "builds its own crawler"
# from the absence of create_crawler(), which is not the same thing.

# The settings block injected into each. Identical numbers to create_crawler's.
SETTINGS = (
    "concurrency_settings=ConcurrencySettings("
    "min_concurrency=1, max_concurrency=1, "
    "desired_concurrency=1, max_tasks_per_minute=60)"
)

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


def crawlers_dir() -> pathlib.Path:
    """Locate the installed tfmkt package inside the vendor's Poetry venv."""
    repo = pathlib.Path.home() / "spine" / "transfermarkt-datasets"
    if not repo.is_dir():
        sys.exit(f"vendor repo not found at {repo}")
    venv = subprocess.check_output(
        ["poetry", "env", "info", "--path"], cwd=repo, text=True
    ).strip()
    hits = list(pathlib.Path(venv).glob("lib/python*/site-packages/tfmkt/common.py"))
    if not hits:
        sys.exit(f"tfmkt/common.py not found under {venv} - is the scraper installed?")
    return hits[0].parent


def patch_self_builder(path: pathlib.Path, cls: str) -> str:
    """Throttle a crawler that constructs its own pool instead of calling
    create_crawler(). Returns 'applied', 'already', or 'MISSING'."""
    source = path.read_text()
    if MARKER in source:
        return "already"

    # `crawler = HttpCrawler()` / `ParselCrawler()`, with nothing between the
    # brackets - anything else means the vendor changed it and we must not guess.
    pattern = re.compile(rf"crawler = {cls}\(\)")
    if not pattern.search(source):
        return "MISSING"

    source = pattern.sub(f"crawler = {cls}({SETTINGS})", source, count=1)

    if "ConcurrencySettings" not in source.split("\n\n")[0] and \
            "from crawlee import ConcurrencySettings" not in source:
        if "from crawlee import Request\n" in source:
            source = source.replace(
                "from crawlee import Request\n",
                "from crawlee import ConcurrencySettings, Request\n", 1)
        else:
            # No `from crawlee import ...` line to extend; add one after the
            # last plain import so it lands above the tfmkt imports.
            source = source.replace(
                "from crawlee.crawlers import",
                "from crawlee import ConcurrencySettings\nfrom crawlee.crawlers import", 1)

    source = source.replace(f"crawler = {cls}(", f"{MARKER}\n    crawler = {cls}(", 1)
    path.write_text(source)
    return "applied"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="report whether the throttle is applied, change nothing")
    args = parser.parse_args()

    pkg = crawlers_dir()
    common = pkg / "common.py"
    targets = {common: MARKER in common.read_text()}
    for name in SELF_BUILDING:
        p = pkg / "crawlers" / name
        if p.exists():
            targets[p] = MARKER in p.read_text()

    if args.check:
        for p, ok in targets.items():
            print(f"  {'APPLIED    ' if ok else 'NOT APPLIED'}  {p.name}")
        missing = [p.name for p, ok in targets.items() if not ok]
        if missing:
            print(f"throttle: NOT fully applied ({', '.join(missing)})")
        else:
            print(f"throttle: APPLIED to all {len(targets)} crawler entry points")
        sys.exit(0 if not missing else 1)

    # 1. create_crawler(), which five of the ten crawlers share.
    source = common.read_text()
    if MARKER in source:
        print("  already applied: common.create_crawler")
    elif ORIGINAL not in source:
        sys.exit(
            f"cannot patch {common}: the code this expects to replace is not there.\n"
            "The vendor has changed create_crawler(). Re-read it and update this "
            "script rather than scraping unthrottled."
        )
    else:
        common.write_text(
            source.replace(IMPORT_FROM, IMPORT_TO, 1).replace(ORIGINAL, THROTTLED, 1))
        print("  applied: common.create_crawler")

    # 2. The five that build their own and would otherwise ignore all of the above.
    failed = []
    for name, cls in SELF_BUILDING.items():
        p = pkg / "crawlers" / name
        if not p.exists():
            continue
        outcome = patch_self_builder(p, cls)
        print(f"  {outcome}: {name} ({cls})")
        if outcome == "MISSING":
            failed.append(name)

    if failed:
        sys.exit(
            f"\nFAILED to throttle: {', '.join(failed)}\n"
            "Do not scrape those assets until this is fixed - they would run at "
            "whatever rate the machine sustains."
        )

    print("\nthrottle applied: 1 request/second, concurrency 1, all entry points")


if __name__ == "__main__":
    main()
