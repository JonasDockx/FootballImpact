"""Item 30: adaptive concurrency for the Transfermarkt scrape (ADR 0017).

WHAT THIS REPLACES. ADR 0009 pinned acquisition at one request per second with a
concurrency of one. The rate half of that was never the binding constraint and
never could have been: with one page in flight the pool cannot start the next
until the previous finishes (`autoscaled_pool.py`, the
`current_concurrency >= desired_concurrency` wait), so the achieved rate is
exactly 1/latency. At the healthy 1.9s per page measured over seasons 2022, 2024
and 2025 that is 32 pages a minute against a cap of 60. The cap has never once
been reached. Raising it alone would change nothing at all.

So the thing that adapts here is CONCURRENCY, and the rate cap is kept as a
genuine ceiling that catches us rather than as the mechanism:

    1 page per 0.25s      max_tasks_per_minute = 240, enforced by Crawlee itself
                          as a sleep between task launches. Never exceeded, at
                          any concurrency, on any night.
    at most 8 in flight   8 x (1 page / 1.9s) is almost exactly 4 pages/second,
                          so the ramp tops out just as the ceiling begins to
                          bite. The two limits agree rather than one hiding the
                          other. On a slow night concurrency is what binds and
                          the ceiling is irrelevant - at 16s per page even 8 in
                          flight is only 0.5 pages/second.

THE CONTROL LAW is additive-increase / multiplicative-decrease, at its most
cautious setting: climb one slot per ten consecutive good responses, and drop
straight back to one on the FIRST bad one. Reaching 8 from 1 costs about 70 good
pages, roughly two minutes on a healthy night; losing it costs one 503. That
asymmetry is the point. The errors this pipeline actually gets are 502/503/504
from a gateway - measured 2026-08-05: 273 503s, 250 502s, 4 504s, one 429, no
403s - which is an origin already struggling, and the way this goes wrong is
adding load to it until a soft failure becomes a hard block.

WHAT COUNTS AS BAD is deliberately narrower than "not 200". A 404 is the site
working perfectly and telling us a page is not there; backing off on it would
mean a season with missing fixtures crawled at concurrency 1 throughout for no
reason. A 403 IS a signal - it is the shape a block takes - so it backs off even
though it is not a 5xx.

WHY NOT A SEMAPHORE IN THE HTTP CLIENT, which is the obvious way to do this and
is wrong here. Crawlee's `request_handler_timeout` defaults to 60 seconds and
covers the whole handler including the fetch. If admission were gated inside
`crawl()`, a request that queued behind seven others would spend that wait on
the timeout clock: at 16s per page the eighth request waits 112s and is failed
as a timeout - the pipeline would manufacture its own failures precisely on the
nights it is already struggling. Shrinking the pool instead makes the waiting
happen in the pool's orchestrator, which has no timeout, so a backed-off run is
simply slower and never counts a request it did not make.

WHY min AND max, not just desired. `AutoscaledPool._autoscale()` runs on a timer
and moves `_desired_concurrency` according to CPU, memory and event-loop load -
never HTTP outcomes. A 32-core desktop always looks idle, so it would climb
straight back to max and undo every backoff within seconds. Pinning min and max
to the same value makes both of its branches no-ops (`desired < max` and
`desired > min` are both false) and leaves this governor the only thing moving
the number. The cost is that genuine local overload no longer scales us down,
which for at most 8 in-flight HTTP requests on this machine is not a real risk.

This file is the source of truth and lives here, not in the vendor clone, for
the same reason throttle-scraper.py does: the clone is disposable and a Poetry
update silently restores the unthrottled original. throttle-scraper.py copies
this module into the venv beside tfmkt and patches create_crawler() to use it.
"""

from __future__ import annotations

import logging

from crawlee.http_clients import ImpitHttpClient

logger = logging.getLogger('gi.adaptive')

# The ceiling, in both of its forms. See the header for why these two numbers
# are the same statement said twice.
MAX_CONCURRENCY = 8
MAX_TASKS_PER_MINUTE = 240

# Consecutive good responses per one extra slot.
RAMP_AFTER = 10

# Bad enough to give up every slot at once. 5xx is the origin failing, 429 is it
# asking us to stop, 403 is what a block looks like. Everything else - including
# 404 and 410, which are healthy answers about missing pages - is good news.
BACKOFF_STATUS = frozenset({403, 429})


def _is_bad(status: int | None) -> bool:
    """A transport error or timeout arrives as None and counts as bad: we cannot
    tell an unwell origin from a dropped connection, and the safe reading of an
    unanswered request is that the far end is in trouble."""
    if status is None:
        return True
    return status >= 500 or status in BACKOFF_STATUS


class Governor:
    """Moves the pool's concurrency in response to HTTP outcomes.

    Not thread-safe and does not need to be: every caller is a coroutine on one
    event loop, and neither `record` nor `_apply` awaits, so no other task can
    observe a half-applied change.
    """

    def __init__(
        self,
        max_concurrency: int = MAX_CONCURRENCY,
        ramp_after: int = RAMP_AFTER,
    ) -> None:
        self._pool = None
        self._max = max_concurrency
        self._ramp_after = ramp_after
        self._n = 1
        self._clean = 0
        self.backoffs = 0

    def attach(self, crawler) -> None:
        """Take hold of the crawler's pool. `_autoscaled_pool` is built in
        `BasicCrawler.__init__`, not in `run()`, so this is safe to call the
        moment the crawler exists - which is also the only moment at which we
        can be sure no request has gone out yet."""
        self._pool = crawler._autoscaled_pool  # noqa: SLF001 - see module header
        self._pool._max_tasks_per_minute = MAX_TASKS_PER_MINUTE  # noqa: SLF001
        self._apply()
        logger.info(
            'gi.adaptive: starting at concurrency 1, ceiling %d in flight '
            'and %d pages/minute (one per %.2fs)',
            self._max, MAX_TASKS_PER_MINUTE, 60 / MAX_TASKS_PER_MINUTE,
        )

    def _apply(self) -> None:
        pool = self._pool
        if pool is None:
            return
        # Assigned min-low first so that max is never briefly below min, which
        # would be an invalid pair if anything did read it in between. Nothing
        # can - there is no await in this method - but the ordering costs
        # nothing and the invariant is then true at every single statement.
        pool._min_concurrency = 1  # noqa: SLF001
        pool._max_concurrency = self._n  # noqa: SLF001
        pool._desired_concurrency = self._n  # noqa: SLF001
        pool._min_concurrency = self._n  # noqa: SLF001

    @property
    def concurrency(self) -> int:
        return self._n

    def record(self, status: int | None) -> None:
        """One response, or one failure. Called from the HTTP client for every
        request the crawler makes, including retries."""
        if not _is_bad(status):
            self._clean += 1
            if self._clean >= self._ramp_after and self._n < self._max:
                self._clean = 0
                self._n += 1
                self._apply()
                logger.info(
                    'gi.adaptive: %d clean in a row, concurrency -> %d/%d',
                    self._ramp_after, self._n, self._max,
                )
            return

        self.backoffs += 1
        self._clean = 0
        if self._n > 1:
            was = self._n
            self._n = 1
            self._apply()
            logger.warning(
                'gi.adaptive: %s, concurrency %d -> 1 (backoff #%d)',
                status if status is not None else 'no response',
                was, self.backoffs,
            )


class AdaptiveImpitHttpClient(ImpitHttpClient):
    """The vendor's HTTP client, reporting every outcome to a Governor.

    The client is the one place that sees every request the crawler makes with
    its status attached - handlers see only what got through, and Crawlee's
    error handlers fire on exhausted retries rather than on each attempt. It
    also leaves tfmkt's own handlers completely untouched.

    `ImpitHttpClient.crawl` does NOT raise on a 4xx or 5xx; it returns the
    result and `BasicCrawler` decides what to do about the status afterwards.
    So the normal path here is a return with a status to read, and the except
    branch is for timeouts and transport errors only.

    Constructed with no arguments beyond the governor, which keeps
    `browser='firefox'` - the impersonation ADR 0009 examined and deliberately
    kept. Do not add arguments here without re-reading that section.
    """

    def __init__(self, governor: Governor, **kwargs) -> None:
        super().__init__(**kwargs)
        self._governor = governor

    async def crawl(self, request, **kwargs):
        try:
            result = await super().crawl(request, **kwargs)
        except Exception:
            self._governor.record(None)
            raise
        self._governor.record(result.http_response.status_code)
        return result
