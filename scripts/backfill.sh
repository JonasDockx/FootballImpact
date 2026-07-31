#!/bin/bash
# Item 30 stage 3: the backfill. The eighteen competitions of stage 1, across the
# eleven seasons 2012-2022, `games` then `game_lineups` and nothing else.
#
#   backfill.sh [first_season] [last_season]      # defaults 2022 down to 2012
#
# Detached, so closing the terminal does not end it (from Windows PowerShell):
#   wsl -d Ubuntu -e bash -lc 'setsid nohup /mnt/c/Users/dockx/Documents/Programmeren/GoalImpact/scripts/backfill.sh >> ~/spine/logs/stage3-nohup.log 2>&1 < /dev/null & echo started'
# Stop it:    wsl -d Ubuntu -e touch  /home/$USER/spine/STOP
# Resume it:  wsl -d Ubuntu -e rm -f /home/$USER/spine/STOP   then launch again
# Watch it:   wsl -d Ubuntu -e bash /mnt/c/.../GoalImpact/scripts/spine-status.sh
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
COMPS="$HOME/spine/scrapes/comps18.json"
# Namespaces every scrape artefact, so stage 3's season 2012 cannot collide with
# stage 2's - same asset, same season, an entirely different parent list. See the
# header of scrape-chunked.sh for what the collision would have done silently.
TAG=s3
STOP="$HOME/spine/STOP"
LOG="$HOME/spine/logs/stage3-backfill.log"
mkdir -p "$(dirname "$LOG")"

[ -s "$COMPS" ] || { echo "missing parents file $COMPS"; exit 1; }

say() { echo "$*" | tee -a "$LOG"; }

say "=== stage 3 backfill: seasons $FIRST..$LAST, started $(date -Is) ==="
if [ -f "$STOP" ]; then
  say "!!! $STOP exists - remove it before starting, or nothing will run"
  exit 1
fi

for SEASON in $(seq "$FIRST" -1 "$LAST"); do
  RAW="$HOME/spine/transfermarkt-datasets/data/raw/transfermarkt-scraper/$SEASON"
  if [ ! -d "$RAW" ]; then
    say "--- season $SEASON: no raw directory, skipping (merge would fail anyway)"
    continue
  fi

  # One competition per chunk for `games`: the parent list is only 18 long, so
  # the default chunking would make the whole 1h17m fixture scrape a single
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

say "=== stage 3 backfill finished $(date -Is) ==="
say "=== next: rebuild the snapshot (just prepare_local + export-duckdb.py), then the census ==="
