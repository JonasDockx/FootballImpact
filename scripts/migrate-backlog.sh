#!/bin/bash
# One-shot migration of .scratch/backlog.md to GitHub issues (2026-07-31).
#
#   scripts/migrate-backlog.sh            # dry run - prints the plan, writes nothing
#   scripts/migrate-backlog.sh --go       # actually creates labels, issues, comments
#
# Reads the frozen backlog, splits each item with scripts/split-backlog.py, and
# files item N as issue #N with its dated sections as comments in order.
#
# The whole script is built around one fragile property: **issue numbers must
# line up with item numbers**. Every ADR and commit message in this repo says
# "item 26", and those references only survive if item 26 becomes issue #26.
# GitHub hands out numbers from a single counter shared by issues and PRs, and
# there is no way to choose one. So the alignment holds only if the counter
# starts at zero and every create lands in order, one at a time. Hence:
#
#   - a preflight that refuses to run if the repo has ever had an issue or a PR
#   - strictly sequential creates, never parallel
#   - a check after every create that the number that came back is the number
#     expected, aborting on the spot if it drifted
#   - a state file, so an interrupted run resumes instead of double-filing
#
# If the preflight fails because the counter is not fresh, alignment is simply
# not available: migrate anyway without it and rewrite the "item N" references
# in docs/adr/ and the git history to the new numbers, or file into a fresh repo.

set -euo pipefail

# Git Bash rewrites arguments that look like POSIX paths into Windows ones - it
# turns "origin/main:.scratch/backlog.md" into "origin\main;.scratch\backlog.md".
# The label names here (status:done, wayfinder:map) survive that because they
# carry no slash, but the margin is thin enough not to rely on.
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.."

BACKLOG=.scratch/backlog.md
WORK=.scratch/migration
STATE=$WORK/created.tsv
GO=no
[ "${1:-}" = "--go" ] && GO=yes

command -v gh >/dev/null || {
  echo "gh not found. Install it (winget install GitHub.cli) and run 'gh auth login'." >&2
  exit 1
}
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated - run 'gh auth login'." >&2; exit 1; }

REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
echo "repo:    $REPO"
echo "mode:    $([ $GO = yes ] && echo 'LIVE - will write' || echo 'dry run')"

# Split the frozen backlog into payloads. Re-derived on every run so the issues
# can never disagree with the archive they came from.
rm -rf "$WORK/issues"
mkdir -p "$WORK/issues"
python scripts/split-backlog.py "$BACKLOG" "$WORK/issues" >/dev/null
MANIFEST=$WORK/issues/manifest.json

# number<TAB>title<TAB>closed<TAB>labels<TAB>comment-count
#
# An unlabelled item emits "-", not an empty field. Tab is an IFS *whitespace*
# character, so bash's `read` collapses a run of them into one delimiter and an
# empty field silently vanishes - which shifted the comment count into $labels
# and made the first unlabelled item try to apply a label called "1".
manifest_tsv() {
  python -c "
import json,sys
sys.stdout.reconfigure(encoding='utf-8')
for i in json.load(open(sys.argv[1],encoding='utf-8')):
    print('\t'.join([str(i['number']),i['title'],'closed' if i['closed'] else 'open',
                     ','.join(i['labels']) or '-',str(i['comments'])]))
" "$MANIFEST"
}

echo "items:   $(manifest_tsv | wc -l), comments: $(manifest_tsv | awk -F'\t' '{s+=$5} END{print s}')"
echo

# ---- preflight: the next number GitHub hands out must be the one we want ----
# Not "the repo must be empty" - that is only true of a fresh run, and this
# script is built to resume. The real invariant is that the issues already on
# the repo are exactly the ones this migration filed, numbered 1..N with no
# gaps, so the next create lands on N+1. A PR anywhere breaks it outright,
# because PRs draw from the same counter.
touch "$STATE"
FILED=$(wc -l <"$STATE" | tr -d ' ')
EXISTING_ISSUES=$(gh issue list --state all --limit 200 --json number --jq 'length')
EXISTING_PRS=$(gh pr list --state all --limit 100 --json number --jq 'length')
MAX_ISSUE=$(gh issue list --state all --limit 200 --json number --jq '[.[].number] | max // 0')
if [ "$EXISTING_PRS" != "0" ]; then
  echo "REFUSING TO RUN: $REPO has $EXISTING_PRS pull request(s), which share the" >&2
  echo "issue number counter, so item N cannot become issue #N. See this script's header." >&2
  exit 1
fi
if [ "$EXISTING_ISSUES" != "$FILED" ] || [ "$MAX_ISSUE" != "$FILED" ]; then
  echo "REFUSING TO RUN: expected $FILED existing issues numbered 1..$FILED (what this" >&2
  echo "migration has filed so far), but found $EXISTING_ISSUES with highest number #$MAX_ISSUE." >&2
  echo "The counter no longer lines up with the item numbers. See this script's header." >&2
  exit 1
fi
# if/fi, not `[ ] && echo` - under `set -e` a false test as the last command
# of the chain would exit the script.
if [ "$FILED" -gt 0 ]; then echo "resuming: #1..#$FILED already filed"; fi

if [ $GO != yes ]; then
  echo "would create these labels:"
  printf '  %s\n' needs-triage needs-info ready-for-agent ready-for-human wontfix \
                  bug enhancement status:done status:rejected status:superseded \
                  status:blocked wayfinder:map wayfinder:research wayfinder:prototype \
                  wayfinder:grilling wayfinder:task
  echo
  echo "would create these issues, in this order:"
  manifest_tsv | awk -F'\t' '{printf "  #%-3s %-6s %-52s %2s comments  %s\n", $1, $3, substr($2,1,52), $5, $4}'
  echo
  echo "re-run with --go to write."
  exit 0
fi

# ---- labels ----------------------------------------------------------------
# Colours: grey for process states, green for done, red for rejected, purple for
# wayfinder. --force so a re-run updates rather than erroring on an existing one.
mklabel() { gh label create "$1" --color "$2" --description "$3" --force >/dev/null; }
mklabel needs-triage        ededed "Maintainer needs to evaluate"
mklabel needs-info          fbca04 "Waiting on more information"
mklabel ready-for-agent     0e8a16 "Fully specified, ready for an AFK agent"
mklabel ready-for-human     1d76db "Needs human implementation"
mklabel wontfix             ffffff "Will not be actioned"
mklabel bug                 d73a4a "Something is broken"
mklabel enhancement         a2eeef "New feature or improvement"
mklabel status:done         0e8a16 "Shipped, gate passed"
mklabel status:rejected     b60205 "Tried, measured, lost; kept as evidence"
mklabel status:superseded   6f42c1 "Replaced; body left for the record"
mklabel status:blocked      d93f0b "Blocked on another issue"
mklabel wayfinder:map       5319e7 "A wayfinder map issue"
mklabel wayfinder:research  c5def5 "Wayfinder ticket: answered by reading or probing"
mklabel wayfinder:prototype c5def5 "Wayfinder ticket: answered by building a throwaway"
mklabel wayfinder:grilling  c5def5 "Wayfinder ticket: answered by interviewing the user"
mklabel wayfinder:task      c5def5 "Wayfinder ticket: answered by doing the work"
echo "labels done"

# ---- issues ----------------------------------------------------------------
while IFS=$'\t' read -r num title state labels ncomments; do
  if grep -q "^$num	" "$STATE"; then
    echo "#$num already filed, skipping"
    continue
  fi

  dir=$WORK/issues/$(printf '%02d' "$num")
  # --label on create, so the issue is never briefly unlabelled.
  args=(--title "$title" --body-file "$dir/body.md")
  if [ "$labels" != "-" ]; then
    IFS=',' read -ra ls <<<"$labels"
    for l in "${ls[@]}"; do args+=(--label "$l"); done
  fi

  url=$(gh issue create "${args[@]}")
  got=${url##*/}
  if [ "$got" != "$num" ]; then
    echo "ABORT: filed item $num but GitHub returned #$got - numbering has drifted." >&2
    echo "Nothing further will be created. $url" >&2
    exit 1
  fi
  printf '%s\t%s\n' "$num" "$url" >>"$STATE"

  for c in "$dir"/comment-*.md; do
    [ -e "$c" ] || continue
    gh issue comment "$num" --body-file "$c" >/dev/null
    sleep 1   # the secondary-rate-limit guard; content creation is throttled hard
  done

  if [ "$state" = closed ]; then
    # "not planned" for the ones that lost or were replaced - a closed state
    # alone does not say which, and that distinction is the point of the record.
    reason=completed
    case "$labels" in *status:rejected*|*status:superseded*) reason="not planned";; esac
    gh issue close "$num" --reason "$reason" >/dev/null
  fi

  echo "#$num $state ${ncomments} comments  $title"
  sleep 1
done < <(manifest_tsv)

# ---- freeze the archive ----------------------------------------------------
# Last, and only on a clean run, so the file never claims a migration that did
# not finish. The header is what stops a future agent finding this file and
# appending to it - the tracker is a replacement, never a hybrid.
FILED=$(wc -l <"$STATE")
if [ "$FILED" -eq 30 ] && ! grep -q "FROZEN ARCHIVE" "$BACKLOG"; then
  tmp=$(mktemp)
  cat >"$tmp" <<EOF
# GoalImpact backlog — FROZEN ARCHIVE

> **Superseded $(date +%F) by GitHub issues on $REPO.**
> Item *N* below is issue **#N** there. Do not append here; this file is kept
> for history only, and the issues are the tracker. See
> [docs/agents/issue-tracker.md](../docs/agents/issue-tracker.md).
>
> Each item's head became the issue body and each dated section became a
> comment, in order — re-derivable from this file with
> \`python scripts/split-backlog.py\`. Section *order* here was rough priority;
> that ordering did not survive the move.

EOF
  tail -n +2 "$BACKLOG" >>"$tmp"
  mv "$tmp" "$BACKLOG"
  echo "froze $BACKLOG"
fi

echo
echo "done. $FILED issues filed."
echo "Spot-check: gh issue view 26 --comments"
