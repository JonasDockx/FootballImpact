#!/bin/bash
# Item 30: watch a running scrape, in real time, without disturbing it.
#
#   spine-watch.sh [logfile]      # default: the most recently written pass-2 log
#
# WHY. Everything worth seeing is already in the log and none of it is findable:
# season 2023's log is 1.4MB, 5167 of its lines are INFO progress chatter, and
# Crawlee does not timestamp anything it writes. So "what is it doing right now,
# and is it going badly?" - the only question anyone actually asks of a run - can
# only be answered by grepping a file after the fact. This filters the log down
# to the lines that carry information and stamps each with the time it appeared.
#
# It cannot slow the scrape down. It only reads a file the crawler is appending
# to, holds no lock and sends no request. Start it and stop it whenever you like;
# Ctrl-C affects nothing but the watcher.
#
# WHAT THE TIMESTAMP MEANS. The time the WATCHER SAW the line, not a time the
# crawler recorded - Crawlee does not record one. On a live tail those differ by
# well under a second, which is the whole point of watching live. On a log opened
# after the fact every line will be stamped "now" and the column is meaningless;
# for history use the chunk headers, which the scrape writes with a real clock.
#
# WHAT IT SHOWS. Chunk boundaries, per-request 5xx with the failing URL, the
# crawler's own progress counter (deduped - it repeats the same figure for
# minutes), and any circuit-breaker trip. Not the response BODIES: every error
# this pipeline gets is 502/503/504 from a gateway, whose body is boilerplate,
# and capturing them would mean patching vendor code to log megabytes of
# "Bad Gateway". Measured 2026-08-05: 273 503s, 250 502s, 4 504s, one 429, no
# 403s - an origin that is unwell, not a door being shut on us.
set -uo pipefail

LOG=${1:-}
if [ -z "$LOG" ]; then
  LOG=$(ls -t "$HOME"/spine/logs/*-p2.log 2>/dev/null | head -1)
fi
[ -n "$LOG" ] && [ -f "$LOG" ] || {
  echo "no log to watch. Pass one explicitly:  spine-watch.sh ~/spine/logs/game_lineups-2023-p2.log"
  exit 1
}

echo "=== watching $(basename "$LOG") - Ctrl-C to stop, the scrape is unaffected ==="
echo "=== healthy is ~1.9s per page and no 5xx; see STAGE3-CHEATSHEET.md ==="
echo

# -n0 so we start at the live edge rather than replaying the file with "now"
# stamps, which would be actively misleading. -F rather than -f: the backfill
# moves to a new log at each asset-season, and -F follows the rename.
# --line-buffered on grep, or the pipe would sit in a 4KB buffer and this would
# show nothing for minutes at a time - which is exactly the fake stall that
# STAGE3-CHEATSHEET.md warns about, and it would be self-inflicted.
tail -n0 -F "$LOG" 2>/dev/null \
  | grep --line-buffered -aE 'status code: [0-9]+|^--- chunk_|CIRCUIT BREAKER|^=== |Crawled [0-9]+/[0-9]+|request_avg_finished_duration|Failed to connect|gi\.adaptive' \
  | while IFS= read -r line; do
      now=$(date '+%H:%M:%S')
      # Strip the ANSI colouring Crawlee emits; it survives the pipe and turns
      # the output into escape soup.
      clean=$(printf '%s' "$line" | sed -e 's/\x1b\[[0-9;]*m//g')

      case "$clean" in
        *"CIRCUIT BREAKER"*)
          printf '%s  ** %s\n' "$now" "$(printf '%s' "$clean" | sed 's/^=== //; s/ ===$//')" ;;

        *"gi.adaptive:"*)
          # The governor's own decisions (ADR 0017). Worth surfacing unfiltered:
          # they are rare by construction - at most seven ramp lines between
          # backoffs - and they are the only place the current concurrency is
          # visible at all. A run that sits at "-> 2/8" all night is telling you
          # the site is refusing roughly every twentieth page.
          printf '%s  %s\n' "$now" \
            "$(printf '%s' "$clean" | sed 's/.*gi\.adaptive: //')" ;;

        *"status code: "*)
          code=$(printf '%s' "$clean" | grep -oE 'status code: [0-9]+' | grep -oE '[0-9]+')
          # The path alone: the host is the same on every line and the full URL
          # pushes the code off the side of a normal terminal.
          path=$(printf '%s' "$clean" | grep -oE 'https://[^ ]+' | head -1 | sed 's|https://[^/]*||')
          printf '%s  %s  %s\n' "$now" "$code" "${path:-?}" ;;

        *"Failed to connect"*)
          printf '%s  ---  connection failed\n' "$now" ;;

        *"request_avg_finished_duration"*)
          # Reported as `None` until the first request completes, which is not a
          # measurement and must not print as a blank one.
          avg=$(printf '%s' "$clean" | grep -oE '[0-9.]+s' | head -1)
          [ -n "$avg" ] || continue
          printf '%s  avg page %s  (healthy ~1.9s)\n' "$now" "$avg" ;;

        *"Crawled "*)
          got=$(printf '%s' "$clean" | grep -oE 'Crawled [0-9]+/' | grep -oE '[0-9]+')
          [ -n "$got" ] || continue
          # Crawlee reprints its counter every few seconds, unchanged more often
          # than not. Every tenth page is frequent enough to show the run is
          # alive and quiet enough to leave the 5xx lines readable - and those
          # are the ones worth seeing.
          [ "$got" -lt "$(( ${last_progress:-0} + 10 ))" ] && continue
          last_progress=$got
          printf '%s  %s\n' "$now" \
            "$(printf '%s' "$clean" | grep -oE 'Crawled [0-9]+/[0-9]+' | head -1)" ;;

        ---*|===*)
          # A new chunk restarts the crawler's counter at zero, so the
          # every-tenth-page threshold has to restart with it or the whole next
          # chunk is silently swallowed.
          last_progress=0
          printf '%s  %s\n' "$now" "$clean" ;;
      esac
    done
