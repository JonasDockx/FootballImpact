# The spine is finished at 32 countries and the 21st century

The spine's width is a **frozen rule**, not an open-ended appetite for more
data. It is finished when, from the **2000/01 season** onward, it holds:

- the **top division** of each of the **32 countries** already in the vendor's
  `config.yml`, plus **that country's domestic cup**;
- the **second division** of Belgium, England, Spain, Italy, Germany and France;
- the European club competitions already pinned;
- national-team **finals tournaments**, plus **World Cup and continental
  qualifying for all six confederations**, plus the **Nations League**.

**Friendlies are excluded.** Six substitutions and half-time rewrites break the
assumption that a stint reflects a competitive coaching judgement (#15).

Roughly 167,000 matches beyond what is on disk on 2026-07-31, about 150 hours of
fixtures and team sheets at the pinned one request per second, before
appearances.

## Why a rule at all

#26 and #15 both said "wider" and neither named a target, so the data layer had
no finish line. At ~70 hours per backfill pass the cost of guessing is measured
in days, and the scrape was already running with its stopping point undecided.

## Width serves three goals, and they are ranked

Grilled 2026-07-31 (#34). Width is wanted for three different reasons, which
pull in different directions, so they are ordered:

1. **Coverage** — the player is in the spine at all. This leads because it is
   what the destination asks for and because *nothing but data can deliver it*:
   no model fix conjures a player who was never scraped.
2. **Quality** — the ratings are defensible. This is a **constraint on how
   coverage grows**, not a separate appetite: #9's rule is that a division never
   arrives without its bridges.
3. **Depth** — careers are not cut off on the left. Last, because #32 refuted
   its headline case: Messi's low number turned out **not** to be caused by his
   missing early years. He is priced honestly; the leaderboard around him was
   inflated.

The ranking is what fixes the pass order below, and it is why every scraped
country gains its domestic cup: the cup is the second tier's bridge to the first,
and goal 2 forbids the tier without it. The 74 pinned competitions carried cups
for eleven countries and **not for France or Belgium** — so Ligue 2 and the
Belgian second tier would have arrived bridgeless.

## Why 32 countries, frozen

"The countries we already scrape" is only a finish line if that set stops
moving. It is therefore **frozen and named**. A 33rd country is not *finishing*
the width — it is a fresh proposal that argues its own case, and by #9's rule it
arrives with its cup or not at all.

## Why 2000, when 2008 is proven and 1946 is what the leaderboard wants

Team sheets demonstrably exist back to **2008** — probed 2026-07-30 on both
Premier League 2008/09 and Euro 2008, 8 of 8 with a full XI and bench (#30). So
2000 is a **choice**, not a limit, and reaching further back later costs the same
hours whenever it is done.

It is knowingly short of what would be needed to reproduce the original's
all-time top 100: 52 of those 100 had retired before 2000, and **not one of them
would have a complete career chart even at full width** — Deco, Gilberto Silva
and Lauren come closest and all three debuted in the late 1990s. That list is a
**validation target for #28**, not a coverage test; it can never pass as one.

## The passes, and why the ruler moves only once

1. The backfill already in flight, unchanged, ending at 2012 — it is dial A, so
   it is *coverage*, and it unblocks #28.
2. Everything the config gains — the ~18 cups, the six second tiers, all
   qualifiers and the Nations League — across 2000–2025.
3. The existing top divisions, 2011 back to 2000.

**One full replay and one re-measure at the end**, not three. Every widening
forces a full replay (#4 was rejected so that this stays true) and invalidates
the calibration: [ADR 0010](0010-scoring-window.md)'s window is pinned to a
2013-07-02 replay start and a 2015-07-01 grading cutoff, both of which are simply
wrong once the spine reaches 2000, and [ADR 0011](0011-impact-index-and-the-career-chart.md)'s
scale constants and the champion log-loss were measured on the 2013-start
population too. **Charts drawn either side of a widening are not comparable.**
The hours do not grow by waiting, but the re-measure is paid once per widening
rather than once per season — so batching beats dribbling.

## Considered options

- **A named test population as the stopping test (rejected, but it changed the
  answer).** The chosen shape at first: a fixed list of players the viewer must
  answer for, each present with some share of their real career minutes. It fell
  to the list actually proposed — the original's all-time top 100 — which cannot
  pass at any width this project will reach. The idea survives as #28's
  validation target, asking whether our number for the *visible slice* of Figo's
  or Scholes's career lands near the original's.
- **A tier rule with no country list (rejected).** "Every first and second tier
  in Europe plus the top league of each other confederation" is principled and
  easy to check an addition against, but it fixes the cost before knowing whether
  the coverage it buys is coverage that was wanted.
- **No finish line — widen forever (rejected).** Honest about how the project
  behaves, but it leaves the data layer and the map with no end, which is the
  whole thing #34 exists to fix.
- **Reaching before 2000 (rejected, reopenable).** Required to reproduce the
  original's leaderboard, and out of scope for this destination.
- **Qualifiers for UEFA only (rejected).** Half the cost, but the spine reaches
  Brazil, Argentina, Colombia, Mexico, MLS, Saudi Arabia, Japan, Korea and
  Australia — the leagues most at risk of being islands, and the ones no domestic
  cup can ever bridge to Europe. CONMEBOL and AFC qualifying are the only
  competitive fixtures where those players meet European ones.
- **Re-measuring after every pass (rejected).** It would show each widening's
  effect in isolation, which is real evidence for #9 and #16, at the cost of
  three re-measures and three mutually incomparable sets of charts.
