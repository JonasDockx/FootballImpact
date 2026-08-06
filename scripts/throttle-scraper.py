#!/usr/bin/env python3
"""Apply item 30's acquisition limits to the vendor's scraper.

The vendor's `transfermarkt-scraper` ships with no rate limiting of any kind --
no delay, no concurrency cap -- and Crawlee's default is to autoscale to whatever
the machine sustains, which on 32 cores means it hits Transfermarkt as hard as it
can. Transfermarkt's robots.txt disallows bots, so this project's position
(item 15, restated in item 30 decision 4) is personal use, limited rate, cache
everything. This patch is what makes that defensible.

WHAT THE LIMIT IS, as of 2026-08-06. ADR 0009 pinned one request per second at a
concurrency of one. ADR 0017 replaces the concurrency half of that with an
adaptive governor: at most eight pages in flight, never more than one page per
0.25 seconds, dropping to one in flight on the first 5xx/429/403 and climbing
back one slot per ten clean responses. It is a real loosening and ADR 0017 says
so plainly. The old fixed rate was never the binding constraint -- at one page in
flight the achieved rate is 1/latency, about 32 pages a minute against a cap of
60 -- so raising the cap alone would have changed nothing; see scripts/gi_adaptive.py.

The self-building crawlers below are NOT adaptive and stay pinned at the ADR 0009
numbers. Item 30's backfill runs `games` and `game_lineups`, both of which go
through create_crawler(); the four below are assets this project does not scrape
in bulk, and the measurement that justifies the loosening was not made on them.

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

ADAPTIVE_MARKER = "# --- GoalImpact item 30: adaptive concurrency (ADR 0017) ---"

# The module that does the work, copied into the venv beside tfmkt on every
# apply so the venv can never hold a stale copy of a control law.
MODULE = "gi_adaptive.py"

# create_crawler() is patched by REPLACING the span between these two anchors
# rather than by matching the code we expect to find. That matters because the
# span has three possible previous states -- pristine vendor, the ADR 0009 fixed
# throttle, and an older revision of this same block -- and a matcher keyed to
# any one of them makes the other two an error the operator has to resolve by
# hand. Both anchors are vendor lines this patch never writes, so they survive
# every re-apply. If either is gone the vendor has restructured the function and
# we must stop rather than guess.
ANCHOR_START = "    failures = []\n"
ANCHOR_END = "    @crawler.failed_request_handler"

ADAPTIVE_BLOCK = f"""    failures = []
    {ADAPTIVE_MARKER}
    # Not a tuning knob to be nudged: these numbers are ADR 0017 and the reasons
    # they are these numbers are in gi_adaptive.py. desired_concurrency starts at
    # 1 because a run must earn its way up from the politest setting -- a fresh
    # process knows nothing yet about how the site is feeling tonight.
    #
    # max_concurrency is set to the ceiling here, but the governor immediately
    # pins min and max together at the live value; Crawlee's own autoscaler moves
    # on CPU and memory, never on HTTP outcomes, and on an idle desktop it would
    # otherwise undo every backoff within seconds.
    from gi_adaptive import (
        AdaptiveImpitHttpClient,
        Governor,
        MAX_CONCURRENCY,
        MAX_TASKS_PER_MINUTE,
    )

    governor = Governor()
    crawler = ParselCrawler(
        http_client=AdaptiveImpitHttpClient(governor),
        concurrency_settings=ConcurrencySettings(
            min_concurrency=1,
            max_concurrency=MAX_CONCURRENCY,
            desired_concurrency=1,
            max_tasks_per_minute=MAX_TASKS_PER_MINUTE,
        ),
    )
    governor.attach(crawler)

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


def module_src() -> pathlib.Path:
    """The governor, in this repository. The checked-in file is the source of
    truth; the copy in the venv is derived and disposable."""
    src = pathlib.Path(__file__).resolve().parent / MODULE
    if not src.is_file():
        sys.exit(f"{MODULE} not found next to this script at {src}")
    return src


def install_module(pkg: pathlib.Path) -> str:
    """Copy the governor into site-packages, beside tfmkt rather than inside it,
    so `import gi_adaptive` resolves without touching the vendor's package
    layout. Returns 'applied', 'already' or 'updated' -- 'updated' is worth
    distinguishing because a venv silently holding last week's control law is
    exactly the failure this whole file exists to prevent."""
    src = module_src()
    dst = pkg.parent / MODULE
    wanted = src.read_text()
    if dst.is_file():
        if dst.read_text() == wanted:
            return "already"
        dst.write_text(wanted)
        return "updated"
    dst.write_text(wanted)
    return "applied"


def patch_create_crawler(common: pathlib.Path) -> str:
    """Replace the crawler-construction span between the two vendor anchors.
    Returns 'applied', 'already' or 'updated'."""
    source = common.read_text()
    start = source.find(ANCHOR_START)
    end = source.find(ANCHOR_END)
    if start < 0 or end <= start:
        sys.exit(
            f"cannot patch {common}: the anchors this expects are not there.\n"
            f"  start {ANCHOR_START.strip()!r}: {'found' if start >= 0 else 'MISSING'}\n"
            f"  end   {ANCHOR_END.strip()!r}: {'found' if end >= 0 else 'MISSING'}\n"
            "The vendor has restructured create_crawler(). Re-read it and update "
            "this script rather than scraping unlimited."
        )

    was_patched = ADAPTIVE_MARKER in source
    patched = source[:start] + ADAPTIVE_BLOCK + source[end:]
    if IMPORT_FROM in patched:
        patched = patched.replace(IMPORT_FROM, IMPORT_TO, 1)

    if patched == source:
        return "already"
    common.write_text(patched)
    return "updated" if was_patched else "applied"


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

    # The governor module is checked as strictly as the patch is. A venv holding
    # a copy that differs from this repository is NOT applied, even though every
    # marker would be present: the markers say the seam is wired up, and only the
    # comparison says the control law behind it is the one we reviewed.
    module_dst = pkg.parent / MODULE
    targets = {
        module_dst: module_dst.is_file()
        and module_dst.read_text() == module_src().read_text(),
        common: ADAPTIVE_MARKER in common.read_text(),
    }
    for name in SELF_BUILDING:
        p = pkg / "crawlers" / name
        if p.exists():
            targets[p] = MARKER in p.read_text()

    if args.check:
        for p, ok in targets.items():
            print(f"  {'APPLIED    ' if ok else 'NOT APPLIED'}  {p.name}")
        missing = [p.name for p, ok in targets.items() if not ok]
        if missing:
            print(f"limits: NOT fully applied ({', '.join(missing)})")
        else:
            print(f"limits: APPLIED - {len(targets) - 1} crawler entry points "
                  f"plus {MODULE}")
        sys.exit(0 if not missing else 1)

    # 1. The governor itself, before anything imports it.
    print(f"  {install_module(pkg)}: {MODULE}")

    # 2. create_crawler(), which five of the ten crawlers share. Adaptive.
    print(f"  {patch_create_crawler(common)}: common.create_crawler (adaptive)")

    # 3. The four that build their own and would otherwise ignore all of the
    #    above. Pinned at the ADR 0009 numbers, not adaptive -- see the header.
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

    print(
        "\nlimits applied:"
        "\n  create_crawler  adaptive, 1..8 in flight, never faster than 1 page/0.25s"
        "\n  self-builders   pinned at 1 request/second, concurrency 1"
    )


if __name__ == "__main__":
    main()
