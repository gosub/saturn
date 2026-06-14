"""Rate-limit-aware work scheduler for the benchmark.

The unit of work is a single (model, case) call, not a whole model. A pool of
`concurrency` worker threads pulls from a shared queue. A 429 never blocks a
worker on a `sleep`: the item is re-queued and the *model* is held back until
its Retry-After elapses, while the worker immediately serves another model whose
turn is ready. Each model still stays sequential (at most one call in flight)
and polite (a `sleep`-second cooldown between its own calls), so concurrency
spreads across models without hammering any single one.

`run_queue` is transport-agnostic: it only knows a CallResult is rate-limited by
its `error_kind`/`retry_after`, and hands every finished call to `on_result`.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Callable, Deque, Dict, Iterable, List, Optional, Tuple

from .client import CallResult, RATE_LIMITED


@dataclass
class _ModelState:
    queue: Deque[Tuple[object, int]] = field(default_factory=deque)  # (case, attempts)
    ready_at: float = 0.0       # monotonic; do not issue a call before this
    in_flight: bool = False     # a call to this model is currently running


def run_queue(items: Iterable[Tuple[str, object]],
              call_fn: Callable[[str, object], CallResult],
              on_result: Callable[[str, object, CallResult], None],
              *, concurrency: int, sleep: float, max_retries: int,
              retry_cap: int = 60,
              stop: Optional[threading.Event] = None) -> None:
    """Run every (model, case) in `items` across `concurrency` workers.

    call_fn   : (model, case) -> CallResult   one HTTP attempt, must not raise
    on_result : (model, case, CallResult)     called once per *terminal* call
                                               (success, hard error, or a 429
                                               that exhausted max_retries)
    """
    stop = stop or threading.Event()

    models: Dict[str, _ModelState] = {}
    order: List[str] = []                       # models in first-seen order
    for model, case in items:
        st = models.get(model)
        if st is None:
            st = models[model] = _ModelState()
            order.append(model)
        st.queue.append((case, 0))

    remaining = sum(len(st.queue) for st in models.values())
    if remaining == 0:
        return
    cond = threading.Condition()

    def pick(now: float):
        """Hold `cond`. Return (model, state, case, attempts) ready to run now,
        or a float = seconds until the soonest cooling model is eligible, or
        None if nothing is runnable except work already in flight."""
        soonest: Optional[float] = None
        for model in order:
            st = models[model]
            if st.in_flight or not st.queue:
                continue
            if st.ready_at <= now:
                case, attempts = st.queue.popleft()
                st.in_flight = True
                return model, st, case, attempts
            wait = st.ready_at - now
            soonest = wait if soonest is None else min(soonest, wait)
        return soonest

    def worker() -> None:
        nonlocal remaining
        while True:
            with cond:
                while True:
                    if stop.is_set() or remaining <= 0:
                        return
                    picked = pick(time.monotonic())
                    if isinstance(picked, tuple):
                        break
                    cond.wait(timeout=picked)   # picked is seconds, or None
                model, st, case, attempts = picked

            call = call_fn(model, case)

            with cond:
                if call.error_kind == RATE_LIMITED and attempts < max_retries:
                    wait = min(call.retry_after or 30, retry_cap)
                    st.queue.appendleft((case, attempts + 1))
                    st.ready_at = time.monotonic() + wait
                    st.in_flight = False
                    cond.notify_all()
                    continue
                st.ready_at = time.monotonic() + sleep
                st.in_flight = False
                remaining -= 1
                cond.notify_all()

            call.retries = attempts
            on_result(model, case, call)

    workers = [threading.Thread(target=worker, daemon=True)
               for _ in range(max(1, concurrency))]
    for t in workers:
        t.start()
    try:
        for t in workers:
            while t.is_alive():
                t.join(timeout=0.2)
    except KeyboardInterrupt:
        stop.set()
        with cond:
            cond.notify_all()
        for t in workers:
            t.join()
        raise
