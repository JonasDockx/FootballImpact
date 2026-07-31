# Issue tracker: GitHub

Issues and PRDs for this repo live as **GitHub issues** on
[`JonasDockx/FootballImpact`](https://github.com/JonasDockx/FootballImpact). Use
the `gh` CLI for all operations; it infers the repo from `git remote -v` when run
inside a clone.

Migrated 2026-07-31 from `.scratch/backlog.md`, which is now a **frozen
archive** — read it for history, never append to it. Item *N* in that file is
issue **#N** here; the numbering was preserved so every `item N` reference in the
ADRs and commit history still resolves. This is a **replacement, never a
hybrid** — two trackers are worse than either.

## What an item is

Items here are not tickets, they are **design records that grow in place**. A
mature item carries: a **Why**, a data-availability note, a design note written
before any typing, the outcome of a grill, then one record per implementation
stage, then what the result bought. See #11 for the fullest short example, #26
and #30 for the long ones.

That shape maps onto GitHub as:

- **The issue body** — the durable head: the `**Why:**` paragraph, the
  data-availability note, and the design note written before any typing. Edited
  only to correct the head itself, never to revise history.
- **One comment per dated record** — the grill outcome, each stage landing, each
  rejection, each "what this bought". Appending a comment is the *only* way an
  item's history grows. Comments are never edited or deleted.

The three properties the old file had are preserved: the reasoning is still
appended and never rewritten, the record still cannot drift from the code, and
an agent still picks up an item in one read (`gh issue view <n> --comments`). The
one property genuinely lost is versioning with the code — the issue no longer
lands in the same commit as the change, so **a commit that implements an item
must name it** (`item 30`, `#30`) for the trail to survive.

## Conventions

- **An issue number is a stable identifier and is never reused.** Refer to an
  item as "item &lt;n&gt;" or "#&lt;n&gt;" in ADRs, commit messages and discussion; both
  forms mean the same issue.
- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a
  heredoc for multi-line bodies. Required: a `**Why:**` paragraph explaining the
  motivation, ideally with the date and whose idea it was. Strongly encouraged: a
  data-availability note saying whether the inputs actually exist.
- **Read an issue**: `gh issue view <n> --comments`. The comments are the
  history, in order — read them.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`
  with appropriate `--label` and `--state` filters.
- **Update an item**: `gh issue comment <n> --body "..."` — **by appending,
  never by rewriting**. A decision that was reversed stays visible, with the
  reversal after it. Stamp the comment with a date in the house style, e.g.
  `**Stage 2 run — REJECTED (2026-07-16).**`
- **Apply / remove labels**: `gh issue edit <n> --add-label "..."` /
  `--remove-label "..."`
- **Close an item**: `gh issue close <n> --comment "..."`. Record the outcome in
  the closing comment. Issues are **never deleted** — a rejected experiment is
  one of the more valuable things in the tracker, so it is closed with
  `status:rejected`, not removed.
- **Supersede an item**: comment pointing at the ADR or issue that replaced it,
  add `status:superseded`, close. Leave the body intact.
- **Priority is issue order, not a label.** The old file expressed rough
  priority by section order. There is no equivalent on GitHub — use a
  milestone or a project board if that ordering turns out to matter, and record
  the choice here.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs
as feature requests; `/triage` reads this flag.)_ This is a solo project; there
is no external contribution surface.

GitHub shares one number space across issues and PRs, so a bare `#42` may be
either — resolve with `gh pr view 42` and fall back to `gh issue view 42`. Note
that issues #1–#30 are the migrated backlog items and are all issues.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <n> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as
tickets.

- **Map**: a single issue labelled `wayfinder:map`, holding the Notes /
  Decisions-so-far / Fog body. `gh issue create --label wayfinder:map`.
- **Child ticket**: an issue linked to the map as a GitHub sub-issue (`gh api` on
  the sub-issues endpoint). Where sub-issues aren't enabled, add the child to a
  task list in the map body and put `Part of #<map>` at the top of the child
  body. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`).
  Once claimed, the ticket is assigned to the driving dev.
- **Blocking**: GitHub's **native issue dependencies** — the canonical,
  UI-visible representation. Add an edge with
  `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`,
  where `<blocker-db-id>` is the blocker's numeric **database id**
  (`gh api repos/<owner>/<repo>/issues/<n> --jq .id`, _not_ the `#number` or
  `node_id`). GitHub reports `issue_dependencies_summary.blocked_by` (open
  blockers only — the live gate). Where dependencies aren't available, fall back
  to a `Blocked by: #<n>, #<n>` line at the top of the child body. A ticket is
  unblocked when every blocker is closed.
- **Frontier query**: list the map's open children (`gh issue list --state open`,
  scoped to the map's sub-issues / task list), drop any with an open blocker
  (`issue_dependencies_summary.blocked_by > 0`, or an open issue in the
  `Blocked by` line) or an assignee; first in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me` — the session's first write.
- **Resolve**: `gh issue comment <n> --body "<answer>"`, then
  `gh issue close <n>`, then append a context pointer (gist + link) to the map's
  Decisions-so-far.

The migrated backlog used a prose `**Prerequisite:** item N` line to express
blocking. Those items carry `status:blocked`; convert them to native
dependencies as each one comes up rather than in a sweep.

## Relationship to ADRs

The tracker holds **what to do and what happened**. The ADRs in
[`docs/adr/`](../adr/) hold **what was decided and why**, for decisions that are
hard to reverse, surprising without context, and the result of a real trade-off.
An issue that produces such a decision links to its ADR and keeps a short
summary; the ADR is the authority. Issues whose decisions fail any of the three
ADR tests keep their reasoning in the issue and no ADR is written — #7 and #11
are the precedent.
