# Acquisition concurrency adapts to the origin's health, under a fixed ceiling

ADR 0009 pinned acquisition at **one request per second, concurrency one**, and
called it a commitment rather than a tuning knob. This ADR loosens it. Acquisition
now runs at **one to eight pages in flight, never faster than one page per 0.25
seconds**, where the number in flight is set by a governor that drops to one on
the first 5xx, 429 or 403 and climbs one slot per ten clean responses.

This is a real loosening and the honest description of it is that throughput was
bought with politeness. It is written down here rather than absorbed as a changed
constant, because ADR 0009 argued that the rate limit *is* what makes crawling
pages robots.txt disallows defensible, and that argument does not survive the
change untouched.

## The old rate was never the constraint it was written as

ADR 0009 pinned two numbers and only one of them ever did anything.

With one page in flight the pool cannot start the next until the previous
finishes — `crawlee/_autoscaling/autoscaled_pool.py`, the
`current_concurrency >= desired_concurrency` wait — so the achieved rate is
exactly `1 / latency`. Against the measured per-page latencies:

| | healthy (1.9s) | season 2023 (4.06s) | 2026-08-05 (16s) |
|---|---|---|---|
| pages/minute achieved | 32 | 15 | 3.75 |
| pages/minute permitted | 60 | 60 | 60 |

The 60/minute cap has never been reached on any night this project has scraped.
It was not restraining anything, and raising it alone would have changed nothing
measurable. **Concurrency was the entire throttle**, and the rate limit was a
statement of intent that happened to sit above the real behaviour.

That matters for how this ADR should be read. Going from 60/minute to 240/minute
looks like a 4x loosening of the written commitment and is not the substance of
the change; going from one page in flight to eight is, and ADR 0009 did not frame
concurrency as the thing being conceded.

## Why the ceiling is 8 in flight and 1 page per 0.25s

The two numbers are one statement said twice, and that is deliberate.

Eight pages in flight at the healthy 1.9s each is 4.2 pages/second. The ceiling
of one page per 0.25s is 4 pages/second. So on a good night the ramp tops out at
almost exactly the moment the ceiling begins to bite, and neither limit hides the
other — if the site got dramatically faster the ceiling would hold the line, and
if it slows down concurrency binds first. At 16s per page, eight in flight is
0.5 pages/second and the ceiling is irrelevant.

The ceiling is enforced by Crawlee itself, as a sleep between task launches
(`max_tasks_per_minute = 240`). It applies at every concurrency, so it is a
genuine backstop and not a derived quantity: no combination of governor states
can exceed it.

## Climb slowly, fall immediately

The control law is additive-increase / multiplicative-decrease at its most
cautious setting: **+1 slot per 10 consecutive good responses, straight to 1 on
the first bad one.** Reaching 8 from 1 costs about 70 good pages, roughly two
minutes on a healthy night. Losing it costs a single 503.

That asymmetry is the whole design, and it follows from what the errors actually
are. Measured over pass 2's season 2023 (2026-08-05): **273 503s, 250 502s, 4
504s, one 429, and no 403s at all.** Those are gateway and origin failures, not a
door being shut on us — the same twenty-page probe run from this laptop and from
an unrelated datacenter IP twenty-six seconds apart returned means of 16.71s and
16.02s, so the site was slow for everyone. An origin already failing is the worst
possible thing to add load to, and the way this change goes wrong is a soft
failure being pushed into a hard block.

**A 404 is not a bad response.** It is the site working correctly and telling us a
page is not there. Backing off on it would mean a season with missing fixtures
crawled at concurrency 1 from end to end for no reason. A 403 *is* treated as
bad despite not being a 5xx, because it is the shape a block takes.

Probing upward necessarily generates the occasional error — that is how the
governor discovers the ceiling has moved. Simulated against an origin that
refuses everything above 4 in flight, the steady-state cost is a **2.40% 5xx
rate**, which sits well under the 10% at which the circuit breaker abandons a
sitting. The two mechanisms do not fight.

## Where it lives, and what stays pinned

`scripts/gi_adaptive.py` is the governor and `scripts/throttle-scraper.py` copies
it into the vendor venv and patches `create_crawler()` to use it — same reasoning
as ADR 0009 gave for the throttle patch: the clone is disposable, Poetry installs
the scraper from git, and an update silently restores the unlimited original.
`--check` now compares the installed module against the checked-in one byte for
byte, because the markers only prove the seam is wired up and only the comparison
proves the control law behind it is the reviewed one.

**The four self-building crawlers stay pinned at the ADR 0009 numbers.** Item 30's
backfill runs `games` and `game_lineups`, both of which go through
`create_crawler()`; `appearances`, `countries`, `national_teams` and
`tournament_editions` build their own pools, are not scraped in bulk here, and are
not what the measurement was taken on.

**The browser impersonation is untouched.** ADR 0009 examined
`ImpitHttpClient(browser='firefox')` and deliberately kept it; the adaptive client
subclasses it and passes no arguments, so the default stands. The honest statement
of what this project now does is: it crawls pages robots.txt asks bots not to
crawl, presenting itself as a browser, for personal use, at up to four pages per
second, backing off to one page at a time the moment the site shows strain.

## Considered options

**Leave ADR 0009 alone.** Rejected, but it was the status quo and it has a real
argument: the commitment is what makes the crawl defensible and 4/s is harder to
defend than 1/s. What decided it is that the backfill is hundreds of hours at the
old rate, that the old rate's *written* number was never reached anyway, and that
a governor which collapses to concurrency 1 on the first sign of strain is in some
lights politer than a fixed rate that keeps hammering regardless of how the origin
is coping.

**Raise `max_tasks_per_minute` and leave concurrency at 1.** This is the literal
reading of "adaptive throttling" and it is a no-op — see the table above. Recorded
because it is the obvious first move and the arithmetic against it is not obvious.

**Adaptive throttling as the referenced write-ups describe it** (slowing down in
response to rate-limit signals). Its premise is that you are going too fast and
the site is telling you so. Measured, we were not: one 429 in a whole season, no
403s, and an identically slow response from a datacenter on another continent.
The remedy for someone else's slow origin is not to go slower.

**A resizable semaphore inside the HTTP client**, which is the obvious way to
implement adaptive concurrency. Rejected on a real failure mode:
`request_handler_timeout` defaults to 60 seconds and covers the fetch, so a
request queued behind seven others at 16s per page waits 112s and is failed as a
timeout. The pipeline would manufacture its own failures on exactly the nights it
is already struggling. Shrinking the pool instead moves the waiting into the
orchestrator, which has no timeout.

**Moving only `desired_concurrency`.** `AutoscaledPool._autoscale()` runs on a
timer and moves it according to CPU, memory and event-loop load — never HTTP
outcomes. A 32-core desktop always looks idle, so it climbs back to max and undoes
every backoff within seconds. Pinning `min` and `max` together makes both of its
branches no-ops and leaves the governor the only thing moving the number. The cost
is that genuine local overload no longer scales us down, which for at most eight
in-flight HTTP requests on this machine is not a real risk.

**Halving on error instead of dropping to 1**, and **doubling on recovery instead
of +1**. Both recover faster from an isolated blip. Both were rejected for the
same reason: after a genuine overload they return quickly to the load that caused
it, and the observed errors arrive in runs rather than singly.

## Consequences

- On a healthy night the backfill runs roughly **8x faster** — about 4 pages/second
  against 0.53. The ~150-hour floor for the remaining pass-2 work becomes ~20 hours.
  On a bad night it is no faster at all, by design.
- Log volume rises with throughput, and the log gains `gi.adaptive` lines at each
  ramp step and each backoff. `scripts/spine-watch.sh` surfaces them.
- The circuit breaker's thresholds are unchanged and were re-checked against the
  new behaviour, not assumed: normal probing costs ~2.4% 5xx against a 10% trip.
- The breaker's latency trigger (`BREAKER_SLOW_S=6`) now measures per-page wall
  time under concurrency, which can inflate under server-side contention. It stays
  at 6s because that is still the signal it was chosen for — an origin in trouble —
  but it is the number to re-read first if sittings start ending early.
- ADR 0009's "one request per second" is superseded by this ADR. Its second
  standing rule, on refreshed snapshots never overriding a release, is untouched.
