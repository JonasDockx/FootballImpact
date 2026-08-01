# A player's rating may start below average, and the club decides which

A player the run has never seen enters at **0** — exactly world-average — unless
the club he is first seen for is an *Unpriced club*, one that plays no league
football anywhere in the run. He then enters **2.58 rating points below
average**: a measured, pinned, dated constant (item 16, 2026-08-01).

The seed is set **once**, at the club he debuted for, and never revisited.

## Why zero was the wrong answer for some of them

In this model 0 does not mean *unknown*. It means *exactly as good as the
average footballer in the world*. For a Premier League debutant that is a
defensible prior. For the eleven men of a fourth-tier side drawn away in the
DFB-Pokal it is a donation, and the arithmetic is unforgiving: the strength gap
at kickoff is zero, so expected goal difference over ninety minutes is zero, so
the Bundesliga side collects a full **+4** of residual for beating a team the
model believed was its equal.

The minnows do sink afterwards — ADR 0006 gives them the largest update factor —
but they are usually knocked out and never play again, so the correction arrives
too late to protect anyone and is never reused. **2,074 of 2,867 clubs** on the
designated run are in this position, **11.4%** of all appearances.

This is a **ranking** bias, not a level shift: a player whose club reaches the
final beats five or six unpriced sides, one knocked out in round one beats none.

## This stays inside "strength is emergent from players"

CONTEXT is explicit that strength is emergent and **there is no separate team
rating**. Seeding off a club brushes against that line, and the line holds,
deliberately rather than by luck:

- **Nothing club-level is ever stored.** `ClubPools` holds which leagues a club
  was seen in, and no rating, no residual and no update. There is nothing to
  read back.
- **The club decides where a rating starts, not how it moves.** From his first
  minute the player is ordinary: his rating changes on his own residuals, sized
  by his own exposure.
- **No re-seeding, ever.** A minnow's man who signs for a league club keeps the
  career he has. Re-seeding on transfer *would* be a club rating in disguise —
  it would make a player's number depend on where he is now rather than on what
  he has done, which is the whole thing the metric refuses to do.

## This is not the acausal warm-up ADR 0009 rejected

"Plays no league football in this run" is a fact about **coverage**, not about
results: which competitions the run happens to carry, the same class of fact as
"this run covers 65 competitions". No rating and no outcome from the future is
read. ADR 0009's rejected warm-up used 2014's *matches* to set 2013's *ratings*;
nothing of that shape happens here.

## Decisions

**The constant is measured, then pinned and dated.** 0.3568 goals per 90 of
shortfall, inverted through the link function (ADR 0007) into
`(2/k)·asinh(shortfall / (180·base)) = 2.58`. It is re-derived, not re-tuned,
when the population changes — ADR 0014, rule 4.

**The seed reads the club and nothing else.** Rejected alternatives are on item
16; the one worth repeating is *seed every debutant at his own club's current
mean*, which is circular exactly where it is needed — a minnow fielding eleven
debutants averages nothing and still lands on 0.

**It reaches the debut match itself.** The seed is written into the frozen
pre-match ratings as well as the new tally. Set it only on the tally and the
lineup his opponents are judged against is still the average one, and the one
match the fix exists for is the one match it misses.

**A national side is never unpriced.** It plays no league either, and reading
that as "unknown" is backwards.

## Considered options

- **Leave every debutant at 0 (rejected).** The status quo, and measurably
  wrong: the gate moved bridge log-loss 0.6457 → 0.6341.
- **Seed at the club's current mean rating (rejected, kept on the shelf).** No
  measured evidence behind it here, and circular where it is needed. It fixes
  everything except this.
- **Both together, as a ladder (rejected).** Two hypotheses in one experiment; a
  tie would teach nothing about which half failed.
- **Damp the update against unpriced sides instead of seeding (rejected).**
  Leaves the *expectation* wrong, so the match is still mispredicted — only the
  bookkeeping gets quieter.
- **Take the sweep's best value, ~15 (rejected).** See ADR 0014: a measured
  constant is not a swept one, and at that size the seed is doing item 9's job.

## Consequences

- **The champion moves to 0.6481** on 85,050 matches (from 0.6503 for the same
  model without the seed on that population).
- **Leaderboard shift in the predicted direction.** Scott Brown — #32's worked
  example of cup-minnow inflation — falls from 16th to 18th.
- **StatsBomb is untouched by construction.** Every StatsBomb competition maps
  to `LEAGUE`, so that corpus has no unpriced club and its pinned 0.6259 cannot
  move.
- **Item 9 is unblocked.** #39 held it back because league levels could not be
  estimated on a table this bias was tilting. The table is level now.
- **Glossary updated:** *Unpriced club* and *Rating seed* added.
