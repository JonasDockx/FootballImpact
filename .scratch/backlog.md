# GoalImpact backlog

Future work, not yet scheduled. Captured during the design grill.

## 1. Store the date each match was played

**Why:** Match chronology is the foundation for a strength- and recency-aware
rating (the road to crediting rule C, see
[ADR 0002](../docs/adr/0002-swappable-goal-crediting-rule.md)). Earlier results
should influence the interpretation of later ones: losing to a *weaker* side
should drop a rating more than losing to a *stronger* side (an expected result),
and older matches should weigh less than recent ones.

**Data availability:** Good. The match date is already in the matches JSON
(`match_date`, e.g. `"2024-07-14"`). We can add it to the `Match` record and
populate it in `DataLoader.loadMatches` whenever we need it — a small, low-risk
change.

## 2. Store each player's date of birth

**Why:** Enables age-aware analysis — comparing a player against peers in the
same age band, and comparing age bands against each other (how does GoalImpact
develop with age?).

**Data availability — CAUTION:** StatsBomb Open Data lineup and event files do
**not** include player date of birth (they carry id, name, nickname, jersey,
country only). Realizing this item will require a *second* data source for DOB
(e.g. a supplementary dataset keyed by player name/id), which reopens the
data-source question we closed in
[ADR 0001](../docs/adr/0001-statsbomb-open-data-as-source.md). Treat sourcing DOB
as its own investigation before committing.
