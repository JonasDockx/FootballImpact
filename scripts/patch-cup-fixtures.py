#!/usr/bin/env python3
"""Give the `games` crawler a route to a CUP's fixture list.

WHAT IS BROKEN. Transfermarkt's overview page for a `pokalwettbewerb` -- every
domestic cup, every qualifying group, both Nations Leagues, every finals
tournament -- no longer carries a link to that competition's fixture list. The
vendor's `games` crawler has exactly two ways of finding one:

  1. a footer link whose text is "All fixtures & results" or "All games";
  2. failing that, any href on the page containing "/gesamtspielplan/".

Measured 2026-08-02 on FA Cup 15/16 and Croky Cup 15/16: the pages come back
200, ~78 KB, with the right title -- and contain NEITHER. So the crawler
enqueues nothing, finishes with `requests_finished: 5, requests_failed: 0`, and
emits zero records. It reports complete success at scraping nothing, which is
this pipeline's signature failure and the reason item 30's smoke tests exist.

League pages are unaffected: `/premier-league/startseite/wettbewerb/GB1/plus/0`
still carries the link, which is why stage 3's eighteen first tiers scraped
cleanly and why nobody noticed. The snapshot's 2,014 FA Cup fixtures came from
the VENDOR's own scrape, taken before the site changed.

Left unpatched this silently deletes 47 of pass 2's 53 competitions -- every cup
and every tournament -- and would have done it after ~150 hours of scraping
rather than before.

THE FIX. One more fallback, after the two above: derive the fixture list from
the parent's own href by swapping `startseite` for `gesamtspielplan`. Verified
by hand before it was written:

    /fa-cup/gesamtspielplan/pokalwettbewerb/FAC?saison_id=2015    149 fixtures
    /croky-cup/gesamtspielplan/pokalwettbewerb/CCB?saison_id=2015  49 fixtures

It is a fallback, not a replacement: where a page still offers a real link, that
link still wins, so nothing that works today changes behaviour. The season is
already in the parent's `seasoned_href`, so this reuses it rather than
re-deriving the query string.

WHY HERE AND NOT IN THE VENDOR CLONE. Same reason as throttle-scraper.py: the
clone is disposable, an edit in it is invisible and is lost to the next
`poetry install`. RE-RUN THIS AFTER ANY VENDOR UPDATE.

    python3 scripts/patch-cup-fixtures.py --check    # is it applied?
    python3 scripts/patch-cup-fixtures.py            # apply (idempotent)
"""

import argparse
import pathlib
import subprocess
import sys

MARKER = "# --- GoalImpact item 30 pass 2: cup fixture-list fallback ---"

# The tail of the vendor's `parse` handler, matched verbatim. If the vendor has
# touched it, this script refuses rather than guessing -- a wrong patch here
# fails the way the bug does, by scraping nothing quietly.
ORIGINAL = """        if next_url:
            await context.add_requests([
                Request.from_url(
                    url=base_url + next_url,
                    label='extract_game_urls',
                    user_data={'base': cb_data},
                )
            ])
"""

PATCHED = f"""        {MARKER}
        # Transfermarkt stopped putting a fixture-list link on cup and
        # tournament overview pages. The list itself is still there, at the
        # same path with `startseite` swapped for `gesamtspielplan`, so derive
        # it rather than emitting nothing. Third in line on purpose: a real
        # link, where the page still has one, keeps winning.
        if not next_url:
            seasoned = parent.get('seasoned_href') or ''
            if '/startseite/' in seasoned:
                next_url = seasoned.replace('/startseite/', '/gesamtspielplan/')
                if next_url.startswith(base_url):
                    next_url = next_url[len(base_url):]

        if next_url:
            await context.add_requests([
                Request.from_url(
                    url=base_url + next_url,
                    label='extract_game_urls',
                    user_data={{'base': cb_data}},
                )
            ])
"""


def games_crawler() -> pathlib.Path:
    """Locate the installed tfmkt games crawler inside the vendor's Poetry venv."""
    repo = pathlib.Path.home() / "spine" / "transfermarkt-datasets"
    if not repo.is_dir():
        sys.exit(f"vendor repo not found at {repo}")
    venv = subprocess.check_output(
        ["poetry", "env", "info", "--path"], cwd=repo, text=True
    ).strip()
    hits = list(pathlib.Path(venv).glob("lib/python*/site-packages/tfmkt/crawlers/games.py"))
    if not hits:
        sys.exit(f"tfmkt/crawlers/games.py not found under {venv}")
    return hits[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="report whether the fallback is applied, change nothing")
    args = parser.parse_args()

    path = games_crawler()
    source = path.read_text()
    applied = MARKER in source

    if args.check:
        print(f"  {'APPLIED    ' if applied else 'NOT APPLIED'}  {path.name}")
        print("cup fixture-list fallback: "
              + ("APPLIED" if applied else "NOT APPLIED - cups and tournaments "
                                           "will scrape as silence"))
        sys.exit(0 if applied else 1)

    if applied:
        print("  already applied: games.parse")
        return
    if ORIGINAL not in source:
        sys.exit(
            f"cannot patch {path}: the code this expects to replace is not there.\n"
            "The vendor has changed the `parse` handler. Re-read it and update this "
            "script rather than scraping cups into silence."
        )
    path.write_text(source.replace(ORIGINAL, PATCHED, 1))
    print("  applied: games.parse cup fixture-list fallback")


if __name__ == "__main__":
    main()
