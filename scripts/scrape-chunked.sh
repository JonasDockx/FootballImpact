#!/bin/bash
# Item 30: a resumable scrape.
#
#   scrape-chunked.sh <asset> <season> <parents.json[.gz]> [chunk_size] [tag]
#
# TAG namespaces the chunk directory, the combined output and the log. It is not
# cosmetic. Stage 2 scraped game_lineups for season 2012 from one parent list;
# stage 3 scrapes the same asset and season from a completely different one (the
# eighteen competitions). Without a tag both land in game_lineups-2012-chunks,
# and the resume logic - which is deliberately dumb, "this chunk has an output
# file, skip it" - would find stage 2's 31 finished chunks plus its .split-done,
# declare the season complete and scrape none of the eighteen competitions. The
# log would say done. The backlog's rule that a stage's evidence is never
# rewritten applies to scrape artefacts too.
#
# WHY. A single tfmkt run is all-or-nothing: it holds everything until the end,
# and if the process dies the work is gone. That is not a theoretical risk here -
# stage 2 lost 13 minutes when WSL shut its own VM down mid-run, with Windows up
# for over three days and no sleep event to explain it, and stage 3 is ~50 hours
# of the same exposure spread over many nights.
#
# So the parent list is split into chunks and each chunk scraped separately. A
# chunk that already has an output file is skipped, so re-running this script
# after any interruption resumes where it stopped. The most a crash can cost is
# one chunk.
#
# Output is written to <out>.part and renamed only on success, so a half-written
# file can never be mistaken for a finished one - which is the whole basis of the
# resume.
#
# The merge is NOT done here: run scripts/merge-scrape.py on the combined output
# once every chunk is present. Keeping scrape and merge apart is deliberate -
# every failure this project has had on this pipeline was in the merge, and a
# merge that fails must never be able to cost a scrape.
#
# STOPPING. `touch ~/spine/STOP` and the run finishes the chunk it is on and
# exits cleanly; re-running resumes. Ctrl-C also works and costs at most the
# in-flight chunk, but the flag is the tidier of the two because it never
# discards work already fetched. Remove the file before resuming.
#
# THE CIRCUIT BREAKER (2026-08-05). A sitting can be worth abandoning, and until
# now nothing could decide that. Pass 2's season 2023 averaged 4.06s per page
# against 1.9s for seasons 2022, 2024 and 2025, logged 527 `503`s against 5 and
# 18, and walked its chunk time from ~280s to ~1900s. It ran all night at 8
# requests a minute for a result a later sitting would have got in a fifth of the
# time. Only four chunks FAILED outright, so a failure count alone would barely
# have noticed - the run was degrading, not breaking, which is why the breaker
# watches page latency and 5xx rate and not just rc.
#
# On 2026-08-05 a twenty-page probe from this laptop and from an unrelated
# datacenter IP, twenty-six seconds apart, returned means of 16.71s and 16.02s -
# a 4% difference across two continents (scripts/latency-probe.sh). The slowness
# is Transfermarkt's and is shared by every client, so the remedy is not to go
# slower, not to change egress and not to change HTTP client. It is to notice and
# come back later. That is all this does.
#
# It writes its reason INTO the STOP file rather than just touching it, so the
# log says why the sitting ended. Every existing `[ -f $STOP ]` test still works,
# a hand-made `touch ~/spine/STOP` still works, and spine-start.ps1 clears it on
# the next start - so the breaker ends a sitting without ever blocking a resume.
#
#   BREAKER=0             disable entirely
#   BREAKER_FAILS=3       consecutive hard chunk failures before tripping
#   BREAKER_5XX_PCT=10    5xx-per-record percentage over one chunk ...
#   BREAKER_5XX_MIN=20    ... ignored on chunks smaller than this, too few to judge
#   BREAKER_SLOW_S=6      avg page seconds (healthy is ~1.9) ...
#   BREAKER_SLOW_N=3      ... sustained over this many consecutive chunks
set -uo pipefail

ASSET=${1:?asset, e.g. game_lineups}
SEASON=${2:?season, e.g. 2012}
PARENTS=${3:?path to the parents file}
CHUNK=${4:-200}
TAG=${5:-}
SUFFIX=${TAG:+-$TAG}

cd "$HOME/spine/transfermarkt-datasets"
export PATH="$HOME/.local/bin:$PATH"
GI=/mnt/c/Users/dockx/Documents/Programmeren/GoalImpact/scripts

python3 "$GI/throttle-scraper.py" --check > /dev/null \
  || { echo "THROTTLE NOT APPLIED - refusing to scrape"; exit 1; }

# Checked here rather than trusted, because its absence is INVISIBLE: without it
# every cup and every tournament returns 0 fixtures, 0 failed requests, and a
# clean exit. A poetry install silently reverts it, and the next thing anyone
# would see is a census short by 47 competitions after the scraping was done.
python3 "$GI/patch-cup-fixtures.py" --check > /dev/null \
  || { echo "CUP FIXTURE-LIST PATCH NOT APPLIED - cups would scrape as silence"; exit 1; }

DIR="$HOME/spine/scrapes/${ASSET}-${SEASON}${SUFFIX}-chunks"
mkdir -p "$DIR"
LOG="$HOME/spine/logs/${ASSET}-${SEASON}${SUFFIX}.log"

# Split the parents once. Chunks are plain .json (jsonl); tfmkt reads either.
if [ ! -f "$DIR/.split-done" ]; then
  echo "splitting $PARENTS into chunks of $CHUNK" >> "$LOG"
  if [[ "$PARENTS" == *.gz ]]; then zcat "$PARENTS"; else cat "$PARENTS"; fi \
    | split -l "$CHUNK" -d -a 4 - "$DIR/chunk_"
  for f in "$DIR"/chunk_*; do mv "$f" "$f.json"; done
  touch "$DIR/.split-done"
fi

TOTAL=$(ls "$DIR"/chunk_*.json 2>/dev/null | wc -l)
echo "=== $ASSET $SEASON: $TOTAL chunks of $CHUNK, started $(date -Is) ===" >> "$LOG"

STOP="$HOME/spine/STOP"

BREAKER=${BREAKER:-1}
BREAKER_FAILS=${BREAKER_FAILS:-3}
BREAKER_5XX_PCT=${BREAKER_5XX_PCT:-10}
BREAKER_5XX_MIN=${BREAKER_5XX_MIN:-20}
BREAKER_SLOW_S=${BREAKER_SLOW_S:-6}
BREAKER_SLOW_N=${BREAKER_SLOW_N:-3}
consec_fail=0
consec_slow=0

# Ends the sitting: records why, in the log and in the STOP file, then exits 3 -
# the same code a requested stop uses, which backfill.sh already unwinds cleanly.
trip() {
  echo "=== CIRCUIT BREAKER: $1 ===" | tee -a "$LOG" >&2
  echo "=== halting at $2, $done_count/$TOTAL done. Nothing is lost; re-run to resume. ===" \
    | tee -a "$LOG" >&2
  echo "circuit breaker $(date -Is): $1" > "$STOP"
  exit 3
}

done_count=0
for chunk in "$DIR"/chunk_*.json; do
  base=$(basename "$chunk" .json)
  out="$DIR/$base.out.jsonl.gz"
  if [ -s "$out" ]; then
    done_count=$((done_count + 1))
    continue
  fi
  # Checked before starting a chunk, never during: a chunk is the unit that
  # either lands whole or is retried whole, and stopping mid-chunk would throw
  # away pages already politely fetched.
  if [ -f "$STOP" ]; then
    echo "=== STOP requested: halting at $base, $done_count/$TOTAL done ===" >> "$LOG"
    exit 3
  fi
  echo "--- $base ($(date '+%H:%M:%S')) ---" >> "$LOG"
  # Remember where the log ends, so the breaker can read back exactly this
  # chunk's own crawler output and nothing from the chunks before it.
  log_mark=$(wc -c < "$LOG")
  poetry run tfmkt "$ASSET" -s "$SEASON" -p "$chunk" 2>>"$LOG" \
    | grep '^{' | gzip > "$out.part"
  # The crawler's own exit status decides, not the size of the output. Two
  # reasons. An empty result is legitimate - AFCN is scraped through a different
  # asset entirely and ARG1 does not exist before 2024, so with one competition
  # per chunk those chunks correctly yield nothing, and a size test would leave
  # them "for retry" forever and never let the season finish. And a size test
  # does not even detect the failure it was written for: `gzip` of no input
  # still writes a valid ~20-byte member, so `-s` is true either way.
  rc=${PIPESTATUS[0]}
  n=$(zcat "$out.part" 2>/dev/null | wc -l)
  # Rename only on success, so an interrupted chunk is retried rather than
  # silently treated as complete.
  if [ "$rc" -eq 0 ]; then
    mv "$out.part" "$out"
    done_count=$((done_count + 1))
    echo "--- $base done: $n records ($done_count/$TOTAL) ---" >> "$LOG"
    [ "$n" -eq 0 ] && echo "--- $base: crawler OK but empty - not covered this season ---" >> "$LOG"
  else
    rm -f "$out.part"
    echo "--- $base FAILED rc=$rc after $n records - left for retry ---" >> "$LOG"
  fi

  [ "$BREAKER" = "1" ] || continue

  # This chunk's slice of the log, and the two health numbers in it. The crawler
  # reports its own average page latency, which is the right measure to compare
  # against the pinned 1.9s baseline: it is per-request and so does not care that
  # a `games` chunk is one competition while a `game_lineups` chunk is 100 games.
  chunk_log=$(tail -c "+$((log_mark + 1))" "$LOG" 2>/dev/null)
  n5xx=$(printf '%s' "$chunk_log" | grep -c 'status code: 5')
  avg=$(printf '%s' "$chunk_log" \
    | grep -oE 'request_avg_finished_duration . [0-9.]+s' | tail -1 | grep -oE '[0-9.]+')

  if [ "$rc" -eq 0 ]; then
    consec_fail=0
  else
    consec_fail=$((consec_fail + 1))
    [ "$consec_fail" -ge "$BREAKER_FAILS" ] \
      && trip "$consec_fail consecutive chunk failures" "$base"
  fi

  # Rate, not count: 527 5xx across a season means nothing without a denominator.
  # But a rate needs a sample. A `games` chunk is ONE competition and some yield
  # a handful of fixtures, so on a 5-record chunk a single blip reads as 20% and
  # would end the night over nothing. Below BREAKER_5XX_MIN records the rate is
  # not evidence and is not consulted - which also disposes of the divide-by-zero
  # on a legitimately empty chunk (AFCN, ARG1 before 2024).
  if [ "${n:-0}" -ge "$BREAKER_5XX_MIN" ] && [ "$n5xx" -gt 0 ]; then
    pct=$((n5xx * 100 / n))
    if [ "$pct" -ge "$BREAKER_5XX_PCT" ]; then
      trip "$n5xx 5xx responses over $n records (${pct}%, limit ${BREAKER_5XX_PCT}%)" "$base"
    fi
  fi

  # Sustained, never a single chunk: latency here is wildly variable even when
  # healthy - the probe saw 0.21s and 55.9s in the same twenty requests - so one
  # slow chunk is noise and only a run of them is a degraded sitting.
  if [ -n "$avg" ] && awk "BEGIN{exit !($avg >= $BREAKER_SLOW_S)}"; then
    consec_slow=$((consec_slow + 1))
    echo "--- $base: avg page ${avg}s (healthy ~1.9s), slow chunk $consec_slow/$BREAKER_SLOW_N ---" >> "$LOG"
    [ "$consec_slow" -ge "$BREAKER_SLOW_N" ] \
      && trip "$consec_slow consecutive chunks averaging >=${BREAKER_SLOW_S}s per page (last ${avg}s, healthy ~1.9s)" "$base"
  else
    consec_slow=0
  fi
done

REMAINING=$((TOTAL - done_count))
if [ "$REMAINING" -gt 0 ]; then
  echo "=== INCOMPLETE: $done_count/$TOTAL chunks. Re-run to resume. ===" >> "$LOG"
  exit 2
fi

COMBINED="$HOME/spine/scrapes/${ASSET}_${SEASON}${SUFFIX}.jsonl.gz"
cat "$DIR"/chunk_*.out.jsonl.gz > "$COMBINED"
echo "=== ALL $TOTAL CHUNKS DONE $(date -Is): $(zcat "$COMBINED" | wc -l) records -> $COMBINED ===" >> "$LOG"
echo "=== now merge with: python3 scripts/merge-scrape.py $ASSET $SEASON $COMBINED ===" >> "$LOG"
