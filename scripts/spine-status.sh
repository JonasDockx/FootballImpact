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

# Either the vendor's acquire script, or one of our own step runners.
PARENT=$(pgrep -f 'acquiring/transfermarkt-scraper.py|stage1-inner' | head -1)
if [ -z "$PARENT" ]; then
  echo
  echo "STATUS: NOT RUNNING"
  echo
  echo "last lines of the log (look for a traceback, or for the final asset):"
  tail -6 "$LOG"
  exit 0
fi

echo "STATUS: RUNNING (pid $PARENT, up $(ps -o etime= -p "$PARENT" | tr -d ' '))"

CHILD=$(pgrep -f 'bin/tfmkt' | head -1)
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
