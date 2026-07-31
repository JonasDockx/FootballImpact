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
