#!/bin/bash
# Item 30: the backfill. A set of competitions across a range of seasons,
# `games` then `game_lineups` and nothing else.
#
#   backfill.sh [first_season] [last_season] [parents.json] [tag] [logname]
#
# Stage 3, which is what this was written for and what its defaults still are:
# the eighteen competitions of stage 1, seasons 2022 down to 2012.
#
#   backfill.sh                                   # = 2022 2012 comps18.json s3
#
# Pass 2 (ADR 0013): the 53 competitions the config gained -- 22 domestic cups,
# six second tiers, the qualifiers and both Nations Leagues -- across 2000-2025.
# Its parents file is built by scripts/widen-config.py.
#
#   backfill.sh 2025 2000 ~/spine/scrapes/comps-pass2.json p2 pass2-backfill.log
#
# Launch it from Windows PowerShell with the launcher, never by hand:
#   powershell -File C:\Users\dockx\Documents\Programmeren\GoalImpact\scripts\spine-start.ps1
# Stop it:    wsl -d Ubuntu -e touch  /home/$USER/spine/STOP   (or close the window)
# Watch it:   wsl -d Ubuntu -e bash /mnt/c/.../GoalImpact/scripts/spine-status.sh
#
# NOT `setsid nohup ... &` inside `wsl -e bash -lc`. That was the documented
# launch until 2026-07-31 and it silently does nothing on this machine: the
# launching wsl.exe exits as soon as it prints `started`, the distro is torn down
# with it, and the job is killed before it writes a byte. The launcher instead
# holds one wsl.exe open in a minimised window for the whole run, and waits for
# the crawler to appear before it claims to have started anything. See the header
# of spine-start.ps1 for how that was measured.
#
# WHY A DRIVER. Stage 3 is ~70 hours of scraping and the machine running it is
# somebody's desktop, not a server - it will be stopped, and it must lose almost
# nothing when it is. scrape-chunked.sh already makes a single asset-season
# resumable; this walks the twenty-two asset-seasons in order, skips whatever is
# already finished, and stops between chunks on request.
#
# HOW TO STOP.  touch ~/spine/STOP
# The current chunk finishes - a few minutes at most - and everything unwinds
# cleanly. Re-running after `rm ~/spine/STOP` picks up at the same chunk. Ctrl-C
# and losing power are also safe; they cost the one chunk that was in flight.
#
# WHAT IS AT RISK AT ANY MOMENT. One chunk: one competition's fixture list for
# `games` (~4 minutes), 100 games' team sheets for `game_lineups` (~3.5 minutes).
# Everything before that is on disk as a finished .out.jsonl.gz.
#
# NEWEST SEASON FIRST, deliberately. If the backfill is only ever half done, the
# half that exists should be the recent seasons - they overlap the scoring window
# (ADR 0010) and so are the half that actually reaches a rating.
#
# ORDER WITHIN A SEASON is forced: `game_lineups` is driven by a parent list of
# games, so that season's `games` must finish first. There is no such dependency
# between seasons, which is why a stop between them costs nothing at all.
set -uo pipefail

FIRST=${1:-2022}
LAST=${2:-2012}

HERE=$(cd "$(dirname "$0")" && pwd)
COMPS=${3:-$HOME/spine/scrapes/comps18.json}
# TAG namespaces every scrape artefact, so stage 3's season 2012 cannot collide
# with stage 2's - same asset, same season, an entirely different parent list -
# and so pass 2's cannot collide with stage 3's. See the header of
# scrape-chunked.sh for what the collision would have done silently. It is also
# why a run must never be given a tag another run has used with a different
# parents file: the resume logic is deliberately dumb.
TAG=${4:-s3}
STOP="$HOME/spine/STOP"
LOG="$HOME/spine/logs/${5:-stage3-backfill.log}"
mkdir -p "$(dirname "$LOG")"

[ -s "$COMPS" ] || { echo "missing parents file $COMPS"; exit 1; }

say() { echo "$*" | tee -a "$LOG"; }

say "=== backfill [$TAG]: seasons $FIRST..$LAST from $(basename "$COMPS"), started $(date -Is) ==="
if [ -f "$STOP" ]; then
  say "!!! $STOP exists - remove it before starting, or nothing will run"
  exit 1
fi

for SEASON in $(seq "$FIRST" -1 "$LAST"); do
  # Created rather than skipped. Stage 3 only ever ran over seasons the vendor
  # had already pulled, so a missing directory there meant a typo and skipping
  # was right. Pass 2 reaches back to 2000, where the vendor's raw tree simply
  # stops - the seasons jump 2005, 2007, 2009, 2011, 2012 - so for most of its
  # range the directory not existing is the normal case and creating it is the
  # job. merge-scrape.py still refuses a missing directory, which keeps the
  # typo guard where a human types a season by hand.
  RAW="$HOME/spine/transfermarkt-datasets/data/raw/transfermarkt-scraper/$SEASON"
  mkdir -p "$RAW"

  # One competition per chunk for `games`: the parent list is tens of entries,
  # so the default chunking would make a season's whole fixture scrape a single
  # all-or-nothing unit - exactly what this stage is trying to avoid.
  say "--- season $SEASON: games ($(date -Is))"
  "$HERE/scrape-chunked.sh" games "$SEASON" "$COMPS" 1 "$TAG"
  rc=$?
  [ "$rc" -eq 3 ] && { say "=== stopped on request during games $SEASON ==="; exit 3; }
  [ "$rc" -ne 0 ] && { say "!!! games $SEASON incomplete (rc=$rc) - re-run to resume"; exit "$rc"; }

  GAMES="$HOME/spine/scrapes/games_$SEASON-$TAG.jsonl.gz"
  ngames=$(zcat "$GAMES" | wc -l)
  say "    games $SEASON: $ngames fixtures"
  if [ "$ngames" -eq 0 ]; then
    say "    season $SEASON has no fixtures in these competitions - nothing to merge"
    continue
  fi

  say "--- season $SEASON: game_lineups, $ngames pages ($(date -Is))"
  "$HERE/scrape-chunked.sh" game_lineups "$SEASON" "$GAMES" 100 "$TAG"
  rc=$?
  [ "$rc" -eq 3 ] && { say "=== stopped on request during game_lineups $SEASON ==="; exit 3; }
  [ "$rc" -ne 0 ] && { say "!!! game_lineups $SEASON incomplete (rc=$rc) - re-run to resume"; exit "$rc"; }

  # Merge per season, not once at the end. The scrape is already durable in the
  # chunk files, so this buys something else: each finished season is banked into
  # the vendor's raw tree on its own, and a merge that goes wrong costs one
  # season's local SQL rather than sitting between the whole backfill and any use
  # of it. Merges are seconds; scrapes are hours (see merge-scrape.py).
  for ASSET in games game_lineups; do
    MARK="$HOME/spine/scrapes/.merged-$ASSET-$SEASON-$TAG"
    if [ -f "$MARK" ]; then
      say "    $ASSET $SEASON already merged"
      continue
    fi
    say "--- merging $ASSET $SEASON"
    if python3 "$HERE/merge-scrape.py" "$ASSET" "$SEASON" \
         "$HOME/spine/scrapes/${ASSET}_${SEASON}-${TAG}.jsonl.gz" >> "$LOG" 2>&1; then
      touch "$MARK"
      say "    $ASSET $SEASON merged"
    else
      say "!!! merge of $ASSET $SEASON FAILED - the scrape is safe, fix and re-run"
      exit 4
    fi
  done

  say "=== season $SEASON COMPLETE $(date -Is) ==="
done

say "=== backfill [$TAG] finished $(date -Is) ==="
say "=== next: predict the census (scripts/census-predict.py), rebuild the snapshot"
say "=== (dbt build --threads 4 --target dev, then scripts/synching/export-duckdb.py),"
say "=== then reproduce the census and run scripts/compare-snapshots.sql"
