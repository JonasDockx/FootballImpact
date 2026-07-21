# Triage Labels

**This repo has no labels.** The issue tracker is
[`.scratch/backlog.md`](../../.scratch/backlog.md) — numbered sections in a
markdown file — so there is nothing to attach a label to. Status is expressed as
prose inside the item; see the status vocabulary in
[issue-tracker.md](./issue-tracker.md).

The skills speak in terms of five canonical triage roles. Map them onto the
backlog like this:

| Role in the skills | How it appears here |
|---|---|
| `needs-triage` | A new item with a `**Why**` and nothing else. Untouched since it was filed. |
| `needs-info` | A `**Data availability — CAUTION:**` note, or an open question in the body. The item cannot proceed until it is answered. |
| `ready-for-agent` | `**Grill done (date), decisions in ADR NNNN … Ready to implement.**` — designed to the point where the work is mechanical. |
| `ready-for-human` | Anything not yet grilled. In this repo design is interviewed before it is built, and the author hand-types the implementation. |
| `wontfix` | `**Stage N run — REJECTED (date)**` or `**Superseded (date) by …**`. The item stays in the file as evidence; it is never deleted. |

When a skill asks to apply a label, append the corresponding prose to the item
instead, dated, following the house style. When a skill asks to *filter* by a
label, read the file and match on these phrasings.

Note that `ready-for-agent` is rarer here than in a typical repo: the working
convention is that non-trivial design is grilled first and the resulting code is
written by hand, so most items reach "designed" without ever becoming
autonomous work.
