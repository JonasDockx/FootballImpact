#!/bin/bash
# Item 30: a resumable scrape.
#
#   scrape-chunked.sh <asset> <season> <parents.json[.gz]> [chunk_size]
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
set -uo pipefail

ASSET=${1:?asset, e.g. game_lineups}
SEASON=${2:?season, e.g. 2012}
PARENTS=${3:?path to the parents file}
CHUNK=${4:-200}

cd "$HOME/spine/transfermarkt-datasets"
export PATH="$HOME/.local/bin:$PATH"
GI=/mnt/c/Users/dockx/Documents/Programmeren/GoalImpact/scripts

python3 "$GI/throttle-scraper.py" --check > /dev/null \
  || { echo "THROTTLE NOT APPLIED - refusing to scrape"; exit 1; }

DIR="$HOME/spine/scrapes/${ASSET}-${SEASON}-chunks"
mkdir -p "$DIR"
LOG="$HOME/spine/logs/${ASSET}-${SEASON}.log"

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

done_count=0
for chunk in "$DIR"/chunk_*.json; do
  base=$(basename "$chunk" .json)
  out="$DIR/$base.out.jsonl.gz"
  if [ -s "$out" ]; then
    done_count=$((done_count + 1))
    continue
  fi
  echo "--- $base ($(date '+%H:%M:%S')) ---" >> "$LOG"
  poetry run tfmkt "$ASSET" -s "$SEASON" -p "$chunk" 2>>"$LOG" \
    | grep '^{' | gzip > "$out.part"
  # Rename only on success, so an interrupted chunk is retried rather than
  # silently treated as complete.
  if [ -s "$out.part" ]; then
    mv "$out.part" "$out"
    done_count=$((done_count + 1))
    echo "--- $base done: $(zcat "$out" | wc -l) records ($done_count/$TOTAL) ---" >> "$LOG"
  else
    rm -f "$out.part"
    echo "--- $base produced NOTHING - left for retry ---" >> "$LOG"
  fi
done

REMAINING=$((TOTAL - done_count))
if [ "$REMAINING" -gt 0 ]; then
  echo "=== INCOMPLETE: $done_count/$TOTAL chunks. Re-run to resume. ===" >> "$LOG"
  exit 2
fi

COMBINED="$HOME/spine/scrapes/${ASSET}_${SEASON}.jsonl.gz"
cat "$DIR"/chunk_*.out.jsonl.gz > "$COMBINED"
echo "=== ALL $TOTAL CHUNKS DONE $(date -Is): $(zcat "$COMBINED" | wc -l) records -> $COMBINED ===" >> "$LOG"
echo "=== now merge with: python3 scripts/merge-scrape.py $ASSET $SEASON $COMBINED ===" >> "$LOG"
