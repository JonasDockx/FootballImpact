# Issue tracker: `.scratch/backlog.md`

Issues and PRDs for this repo live as numbered items in a single local file,
[`.scratch/backlog.md`](../../.scratch/backlog.md). **This repo does not use
GitHub issues**, and external PRs are not a triage surface.

The file is tracked in git, so an item's history and the code that implemented
it arrive in the same commits. `git log -p .scratch/backlog.md` is the audit
trail.

## Why a file and not GitHub

The items are not tickets, they are **design records that grow in place**. A
mature item carries: a **Why**, a data-availability note, a design note written
before any typing, the outcome of a grill, then one appended paragraph per
implementation stage, then what the result bought. See item 11 for the fullest
example. Three properties follow, and all three are lost on a hosted tracker:

- **Versioned with the code.** The reasoning lands in the same commit as the change.
- **One read.** An agent picks up the whole backlog in a single file read.
- **Appending is free**, so the record does not drift from the code.

Reconsider only if the project gains collaborators, or if filing items away from
the machine becomes a real need. Migrating is a replacement, never a hybrid —
two trackers are worse than either.

## Conventions

- **An item is a `## <n>. <Title>` section.** Numbers are stable identifiers and
  are never reused; the *order* of sections is rough priority, so a new item may
  be inserted anywhere. Numbering gaps are normal.
- **Refer to an item as "item <n>"**, in ADRs, commit messages and discussion.
- **Create an item**: append a new section with the next unused number. Required:
  a `**Why:**` paragraph explaining the motivation, ideally with the date and
  whose idea it was. Strongly encouraged: a data-availability note saying whether
  the inputs actually exist.
- **Update an item**: edit it **in place, by appending**. Never rewrite history —
  a decision that was reversed stays visible, with the reversal after it. Stamp
  appended paragraphs with a date, e.g. `**Stage 2 run — REJECTED (2026-07-16).**`
- **Close an item**: mark the heading `— DONE`, or record the outcome inline
  (`**Stage 2 run — REJECTED**`). Items are **not deleted**; a rejected experiment
  is one of the more valuable things in the file.
- **Supersede an item**: append a note pointing at the ADR or item that replaced
  it. Leave the original body intact.

## Status vocabulary

There are no labels. Status is prose, and these phrasings are the house style:

| Phrasing | Meaning |
|---|---|
| `— DONE` in the heading | Shipped, gate passed |
| `**Grill done (date), decisions in ADR NNNN**` | Designed, ready to implement |
| `**Stage N landed (date)**` | Partially shipped, gate held |
| `**Stage N run — REJECTED (date)**` | Tried, measured, lost; kept as evidence |
| `**Superseded (date) by …**` | Replaced; body left for the record |
| `**Prerequisite:** item N` | Blocked |
| No status line | Not started |

## When a skill says "publish to the issue tracker"

Append a section to `.scratch/backlog.md`.

## When a skill says "fetch the relevant ticket"

Read `.scratch/backlog.md` and find the numbered item.

## Relationship to ADRs

The backlog holds **what to do and what happened**. The ADRs in
[`docs/adr/`](../adr/) hold **what was decided and why**, for decisions that are
hard to reverse, surprising without context, and the result of a real trade-off.
An item that produces such a decision links to its ADR and keeps a short summary;
the ADR is the authority. Items whose decisions fail any of the three ADR tests
keep their reasoning in the backlog and no ADR is written — items 7 and 11 are
the precedent.
