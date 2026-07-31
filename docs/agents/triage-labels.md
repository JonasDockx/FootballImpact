# Triage Labels

Labels on [`JonasDockx/FootballImpact`](https://github.com/JonasDockx/FootballImpact)
issues. Created 2026-07-31 with the migration from `.scratch/backlog.md`; before
that this repo had no labels and status was prose inside each item.

## Canonical triage roles

The skills speak in terms of five canonical state roles and two category roles.
This repo uses the default strings — label name equals role name, so no mapping
is needed:

| Role in the skills | Label here |
|---|---|
| `needs-triage` | `needs-triage` |
| `needs-info` | `needs-info` |
| `ready-for-agent` | `ready-for-agent` |
| `ready-for-human` | `ready-for-human` |
| `wontfix` | `wontfix` |
| `bug` | `bug` |
| `enhancement` | `enhancement` |

Note that `ready-for-agent` is rarer here than in a typical repo: the working
convention is that non-trivial design is grilled first, so most items reach
"designed" without ever becoming autonomous work.

## House status labels

The old backlog expressed status as prose. Those phrasings became labels so the
same states stay queryable — they are **this repo's vocabulary**, additional to
the canonical roles above, not a replacement for them:

| Label | Old backlog phrasing | Meaning |
|---|---|---|
| `status:done` | `— DONE` in the heading | Shipped, gate passed |
| `status:rejected` | `**Stage N run — REJECTED (date)**` | Tried, measured, lost; kept as evidence |
| `status:superseded` | `**Superseded (date) by …**` | Replaced; body left for the record |
| `status:blocked` | `**Prerequisite:** item N` | Blocked on another issue |

`status:done`, `status:rejected` and `status:superseded` all sit on **closed**
issues — the label says *why* it closed, which a bare closed state does not.
`status:blocked` sits on open issues; prefer a native dependency edge (see the
Wayfinding section of [issue-tracker.md](./issue-tracker.md)) once the blocker
is expressed that way, and drop the label when you do.

An item with a `**Why**` and nothing else is `needs-triage`. An item with an
open `**Data availability — CAUTION:**` note or an unanswered question is
`needs-info`.

## Wayfinder labels

Used by `/wayfinder`; see the Wayfinding operations section of
[issue-tracker.md](./issue-tracker.md).

| Label | Meaning |
|---|---|
| `wayfinder:map` | The map issue — the canonical artifact of a wayfinding effort |
| `wayfinder:research` | Child ticket: a question answered by reading or probing |
| `wayfinder:prototype` | Child ticket: a question answered by building a throwaway |
| `wayfinder:grilling` | Child ticket: a question answered by interviewing the user |
| `wayfinder:task` | Child ticket: a question answered by doing the work |
