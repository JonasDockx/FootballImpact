# The grid is scored on a window, not on the whole replay

Every match is replayed. Not every prediction is **graded**. On the
Transfermarkt spine the tuning harness scores only goals from **2015-07-01**
onward, while replaying from 2013-07-02 exactly as before, and reports the
whole-period log-loss beside the windowed one.

## Why

Transfermarkt has no lineup before July 2013 (ADR 0009), so every career open at
that moment is *left-censored* and the entire population starts at rating 0.
While ratings sit near zero they are also near *identical*, so the strength gap
at kickoff is near zero and the model predicts close to 50/50 — for a reason
that has nothing to do with the knobs being tuned.

`K0` and `H` are precisely the knobs that govern how fast a rating escapes zero.
Scoring the burn-in therefore rewards whichever settings climb out of the hole
quickest, which is not the same question as which settings predict football
best. It is not a rounding error: **10.9% of all credited goals fall in 2013–14
and 17.8% through 2015**, so roughly a seventh of the objective was being spent
on the model discovering its own population.

Two full seasons is the cutoff because `H = 4,000` minutes is about 44 full
matches — a little over one season for a regular starter — so by mid-2015 the
median regular has a settled rather than a discovered rating.

## This is not the warm-up pass ADR 0009 rejected

ADR 0009 declined "a warm-up pass over 2013–2014 to seed ratings" because it
would use 2014's matches to set 2013's ratings, making every later rating
acausal and contaminating every run forever.

Nothing of that applies here. **No rating changes.** The replay is the same
replay, in the same chronological order, and every rating is still read only
from matches played before it. The only thing that changes is which predictions
are counted when the harness grades itself. Nothing is fitted to the future,
because nothing is fitted at all — the window is a fixed, dated constant, never
a swept knob.

## Decisions

**The window is a pinned constant with a stated reason, not a tuned one.**
Sweeping the cutoff would turn a diagnostic into a knob and invite fitting the
objective to whatever range flatters it.

**Both numbers are always printed.** The grid table carries a `logloss` column
(the windowed score, which the grid picks on) and a `whole` column beside it.
Reporting only the windowed number would hide the choice; reporting only the
whole number would ignore why the window exists.

**The window is per spine.** StatsBomb keeps its entire range — its population,
era and burn-in are different, and its pinned 0.6259 must reproduce
byte-identically. On StatsBomb the two columns are equal by construction, which
is also how the wiring is verified.

## Considered options

- **Score the whole replay (rejected).** The status quo. Simple, but spends a
  seventh of the objective on a period where the knobs are not what is being
  measured, and biases `K0`/`H` toward faster updates than the steady state
  deserves.
- **A warm-up pass that seeds 2013 from later matches (rejected by ADR 0009,
  and again here).** Acausal; contaminates every rating in every future run to
  fix a burn-in that is a fixed, one-time cost at the window's leading edge and
  shrinks as the data grows.
- **Drop the 2013–2015 matches from the run entirely (rejected).** Throws away
  real exposure and real careers to fix a reporting problem. Those matches
  still tell the model who was good; they just should not be used to grade it.
- **Sweep the cutoff as a knob (rejected).** Would let the harness choose the
  window that makes its own score look best.

## Consequences

- **Measured effect is real but modest.** Over the full ingest the windowed
  score is **0.6505** against **0.6510** whole-period at the pre-tuning knobs,
  and **0.6502 / 0.6508** at the champion. The burn-in penalty is about 0.0005 —
  worth removing from the objective, not worth alarm.
- **The champion is quoted on the window.** `CHAMPION = 0.6502` for
  Transfermarkt is the windowed number; 0.6508 is its whole-period counterpart.
  Any future comparison must use the same window, which is why it is a pinned
  constant rather than a run-time choice.
- **Slices are unaffected in practice.** The pinned `GB1/2024` and pooled
  regression runs sit entirely after the cutoff, so their two columns are equal
  — which is the check that the window only bites where there is something
  before it.
- **Glossary updated:** *Scoring window* added. *Left-censored career* already
  named the condition this addresses; the window is what the project does about
  it.
- **Item 20's left-censoring diagnostic is superseded.** It proposed reporting
  how much of the run was burn-in. The window handles the place where it
  mattered — the tuning objective — so a separate report would be reading
  rather than deciding.
