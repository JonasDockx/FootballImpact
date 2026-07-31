#!/bin/bash
# Item 30: is the spine scrape running, and is it actually doing anything?
#
#   wsl -d Ubuntu -e bash /mnt/c/Users/dockx/Documents/Programmeren/GoalImpact/scripts/spine-status.sh
#
# Why this exists rather than a tail of the log: the vendor's script captures the
# crawler's output into a pipe and only writes to the log when a whole ASSET
# finishes, and its stdout goes through gzip, which buffers. So a healthy run
# looks frozen for hours at a time. The only honest live signal is the crawler
# process reading bytes off its sockets - /proc/<pid>/io rchar - which is what
# this samples.

# Whichever log was written to most recently, so this keeps working as stages
# come and go rather than being pinned to stage 1's file.
LOG=$(ls -t "$HOME"/spine/logs/*.log 2>/dev/null | head -1)
[ -z "$LOG" ] && { echo "no log in ~/spine/logs"; exit 1; }

echo "time: $(date '+%H:%M:%S')"
echo "log:  $(basename "$LOG")"

# Stage 3 spans many nights and twenty-two asset-seasons, so "is it running" is
# only half the question - the other half is how much of the backfill is done.
# Printed whether or not a crawler is up, because that is exactly what you want
# to know after stopping it.
[ -f "$HOME/spine/STOP" ] && echo "STOP FLAG IS SET - the run will halt after the current chunk"
if ls "$HOME"/spine/scrapes/game_lineups-*-chunks > /dev/null 2>&1; then
  echo
  echo "stage 3 progress (chunks complete / total, per asset-season):"
  for d in "$HOME"/spine/scrapes/*-chunks; do
    t=$(ls "$d"/chunk_*.json 2>/dev/null | wc -l)
    c=$(ls "$d"/chunk_*.out.jsonl.gz 2>/dev/null | wc -l)
    name=$(basename "$d" -chunks)
    # The merge marker is named for the same asset-season-tag triple as the
    # chunk directory, so it is exactly ".merged-<name>" and needs no parsing.
    merged=""
    [ -f "$HOME/spine/scrapes/.merged-$name" ] && merged="  merged"
    printf '  %-28s %4d/%-4d%s\n' "$name" "$c" "$t" "$merged"
  done
fi

# Detect on `tfmkt` itself, never on the name of whichever script is driving it.
# The first version of this listed driver names - and stage 2's driver was not on
# the list, so it reported NOT RUNNING for a perfectly healthy scrape. That was
# believed, a phantom cause was diagnosed, and a SECOND scrape was started
# alongside the first: two requests a second, against the one ADR 0009 pins.
# The crawler process is the thing that is actually doing the work, so it is the
# thing to look for.
CRAWLERS=$(pgrep -f 'bin/tfmkt' | wc -l)

# More than one crawler at a time IS the breach. Say so before anything else.
if [ "$CRAWLERS" -gt 1 ]; then
  echo
  echo "*** WARNING: $CRAWLERS crawlers are running at once ***"
  echo "*** That is $CRAWLERS requests/second against the 1/second ADR 0009 pins."
  echo "*** Stop all but one before doing anything else:"
  pgrep -af 'bin/tfmkt' | sed 's/^/      /'
  echo
fi

PARENT=$(pgrep -f 'bin/tfmkt' | head -1)
if [ -z "$PARENT" ]; then
  echo
  echo "STATUS: NOT RUNNING"
  echo
  echo "last lines of the log (look for a traceback, or for the final asset):"
  tail -6 "$LOG"
  exit 0
fi

echo "STATUS: RUNNING (crawler pid $PARENT, up $(ps -o etime= -p "$PARENT" | tr -d ' '))"

# Whatever script is driving it, named for information only - never used to
# decide whether anything is running.
DRIVER=$(ps -o args= -p "$(ps -o ppid= -p "$PARENT" | tr -d ' ')" 2>/dev/null | head -c 90)
[ -n "$DRIVER" ] && echo "driver: $DRIVER"

# Chunked runs can say exactly how far along they are.
CHUNKDIR=$(ls -td "$HOME"/spine/scrapes/*-chunks 2>/dev/null | head -1)
if [ -n "$CHUNKDIR" ]; then
  total=$(ls "$CHUNKDIR"/chunk_*.json 2>/dev/null | wc -l)
  done_n=$(ls "$CHUNKDIR"/chunk_*.out.jsonl.gz 2>/dev/null | wc -l)
  echo "chunks: $done_n of $total complete  ($(basename "$CHUNKDIR"))"
fi

CHILD=$PARENT
if [ -n "$CHILD" ]; then
  # The command line is "<venv>/bin/python <venv>/bin/tfmkt <asset> -s <season> ...",
  # so pick the asset by name rather than by position.
  ASSET=$(ps -o args= -p "$CHILD" \
    | grep -oE '\b(clubs|players|appearances|games|game_lineups|competitions|countries)\b' \
    | head -1)
  echo "current asset: $ASSET (running $(ps -o etime= -p "$CHILD" | tr -d ' '))"
  r1=$(awk '/^rchar/{print $2}' "/proc/$CHILD/io" 2>/dev/null)
  sleep 10
  r2=$(awk '/^rchar/{print $2}' "/proc/$CHILD/io" 2>/dev/null)
  if [ -n "$r1" ] && [ -n "$r2" ]; then
    d=$((r2 - r1))
    echo "network read in 10s: $d bytes"
    if [ "$d" -gt 5000 ]; then
      echo "  -> FETCHING PAGES, all well"
    else
      echo "  -> little or no reading; if this repeats, something is wrong"
    fi
  fi
else
  echo "current asset: none (between assets - merging, which takes seconds)"
fi

echo
echo "crawler's own progress (this run logs it live; the vendor's runs did not):"
grep -oE 'Crawled [0-9]+/[0-9]+ pages, [0-9]+ failed' "$LOG" | tail -2

echo
echo "steps finished so far:"
grep -E 'Scraped .* new records|Merged result|=== .* (started|scraped|MERGED|MERGE FAILED|ALL DONE)' "$LOG" | tail -8
