#!/usr/bin/env python3
"""Split .scratch/backlog.md into per-item GitHub issue payloads.

    python scripts/split-backlog.py .scratch/backlog.md <outdir>

Written for the 2026-07-31 migration of the backlog to GitHub issues, and kept
so the migration can be re-derived from the frozen archive rather than trusted.
Driven by scripts/migrate-backlog.sh.

The mapping, which is the whole design: an item's **head** - the `**Why:**`, the
data-availability note, the design note written before any typing - becomes the
issue body, and every **dated** section after it becomes one comment, in file
order. That is what the repo's "append, never rewrite" rule looks like on
GitHub: history lands as comments, which cannot be silently revised, and the
body holds only the durable head.

Two shapes of dated section exist in the file and both are split points:
a `### Heading (2026-07-23)` (the long items) and a `**Stage 2 run - REJECTED
(2026-07-16)**` bold lead-in (the short ones). The date may carry a qualifier -
`(grilled 2026-07-23)`, `(shipped 2026-07-26)` - which an earlier version of
this regex missed, silently swallowing six of item 26's stage records into a
neighbouring comment.

Nothing is ever reordered: the head is the contiguous prefix before the first
dated section, so a body can only ever hold text that already came first. Prose
is copied verbatim - this script never rewrites a word.
"""

import json
import os
import re
import sys

SRC = sys.argv[1]
OUT = sys.argv[2]

ITEM_RE = re.compile(r"^## (\d+)\.\s+(.*)$")
# The date may carry a qualifier: "(2026-07-23)", "(grilled 2026-07-23)",
# "(shipped 2026-07-26)", "(user, 2026-07-29)".
DATE = r"\d{4}-\d{2}-\d{2}"
# A dated "### Heading (2026-07-23)" or a dated "**Stage 2 run — REJECTED (2026-07-16)"
SPLIT_RE = re.compile(r"^(?:###\s+.*%s|\*\*[^*]*%s)" % (DATE, DATE))
# Dated sections that are still *head* material, not chronological history.
# "**Why (user, 2026-07-29):**" opens an item; it is never a history entry.
HEAD_KEEP_RE = re.compile(
    r"^(?:\*\*Why\b|###\s+(?:Data availability|What is actually missing))", re.I
)

lines = open(SRC, encoding="utf-8").read().split("\n")

# Slice the file into items.
items, cur = [], None
for line in lines:
    m = ITEM_RE.match(line)
    if m:
        cur = {"number": int(m.group(1)), "title": m.group(2).strip(), "lines": []}
        items.append(cur)
    elif cur is not None:
        cur["lines"].append(line)

manifest = []
for it in items:
    body_lines, comments, cursor = [], [], None
    for line in it["lines"]:
        # HEAD_KEEP only applies while still in the head — once history has
        # started, nothing may jump back into the body, or the record reorders.
        if SPLIT_RE.match(line) and not (cursor is None and HEAD_KEEP_RE.match(line)):
            cursor = [line]
            comments.append(cursor)
        elif cursor is None:
            body_lines.append(line)
        else:
            cursor.append(line)

    title = it["title"]
    # "— DONE" in the heading is a status marker, not part of the title.
    heading_done = bool(re.search(r"—\s*DONE\s*$", title))
    title = re.sub(r"\s*—\s*DONE\s*$", "", title).strip()

    full = "\n".join(it["lines"])
    # Items 14 and 18 shipped but their headings were never marked. The record
    # inside the item is the more reliable signal: an "Outcome ... — DONE"
    # section (18), or a top-level "**Shipped (date):**" entry (14). Matched at
    # the start of a line so a per-stage "(shipped 2026-07-26)" heading, which
    # closes a stage and not the item, does not count.
    outcome_done = bool(
        re.search(r"^###\s+.*Outcome.*—\s*DONE", full, re.M)
        or re.search(r"^\*\*Shipped \(", full, re.M)
    )
    rejected = bool(re.search(r"\*\*Stage.*REJECTED", full))
    superseded = bool(re.search(r"\*\*Superseded", full))
    closed = heading_done or outcome_done or rejected or superseded

    labels = []
    if heading_done or outcome_done:
        labels.append("status:done")
    if rejected:
        labels.append("status:rejected")
    if superseded:
        labels.append("status:superseded")
    if re.search(r"\*\*Prerequisite:\*\*", full):
        labels.append("status:blocked")
    # Only meaningful while the item is still live — on a closed item the
    # "Ready to implement" line is history, not a current state.
    if not closed and re.search(r"Ready to implement", full):
        labels.append("ready-for-agent")

    d = os.path.join(OUT, "%02d" % it["number"])
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "body.md"), "w", encoding="utf-8").write(
        "\n".join(body_lines).strip() + "\n"
    )
    for i, c in enumerate(comments, 1):
        open(os.path.join(d, "comment-%02d.md" % i), "w", encoding="utf-8").write(
            "\n".join(c).strip() + "\n"
        )

    manifest.append(
        {
            "number": it["number"],
            "title": title,
            "closed": bool(closed),
            "labels": labels,
            "comments": len(comments),
            "body_chars": len("\n".join(body_lines).strip()),
            "total_chars": len(full),
        }
    )

manifest.sort(key=lambda m: m["number"])
open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8").write(
    json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
)

# Round-trip check: every byte of every item must survive into body+comments.
print("%-4s %-58s %5s %6s %6s" % ("#", "title", "cmts", "body", "total"))
for m in manifest:
    print(
        "%-4d %-58s %5d %6d %6d"
        % (m["number"], m["title"][:58], m["comments"], m["body_chars"], m["total_chars"])
    )
