"""A worker: claim a run, execute it, release it.

The interesting decision is what a worker does with the exceptions a handler can
raise. They are not all failures:

    ApprovalRequired -> SUSPENDED   (waiting for a human)
    BudgetExceeded   -> CANCELED    (a guardrail stopped it)
    RetryLater       -> still RUNNING, but scheduled for later
    anything else    -> FAILED

If this were one big try/except the run would be marked FAILED for all four, and
a paused refund would look like a broken one.
"""

from __future__ import annotations

import json
import threading
import time
from typing import Any, Callable

from duragent.approvals import ApprovalRequired, expire_stale
from duragent.budgets import BudgetExceeded
from duragent.retry import RetryLater
from duragent.runs import (
    CANCELED,
    COMPLETED,
    FAILED,
    SUSPENDED,
    claim,
    get_run,
    heartbeat,
    release,
    schedule,
    set_status,
)

# A handler receives (run_id, payload) and is responsible for invoking the graph.
# Raising one of the control-flow exceptions above changes how the run is closed.
Handler = Callable[[str, dict[str, Any]], None]


class Worker:
    """One process competing for runs.

    While a handler runs, a daemon thread renews the lease. If the process dies,
    the renewal simply stops, the lease expires, and another worker picks the run
    up - replaying finished nodes from the journal instead of repeating them.

    A second daemon thread sweeps overdue approvals every ``sweep_every`` seconds,
    because nothing else in the system would ever close them.
    """

    def __init__(self, conn, worker_id: str, handlers: dict[str, Handler], *,
                 lease: float = 30.0, heartbeat_every: float = 5.0,
                 sweep_every: float | None = 30.0,
                 clock: Callable[[], float] = time.time) -> None:
        if heartbeat_every >= lease:
            raise ValueError("heartbeat_every must be shorter than the lease")
        self.conn = conn
        self.worker_id = worker_id
        self.handlers = handlers
        self.lease = lease
        self.heartbeat_every = heartbeat_every
        self.sweep_every = sweep_every
        self.clock = clock
        self._in_flight: set[str] = set()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._sweep_thread: threading.Thread | None = None

    def poll_once(self) -> bool:
        """Claim and execute at most one run. Returns False when the queue is empty."""
        self._ensure_threads()
        run_id = claim(self.conn, self.worker_id, lease=self.lease, clock=self.clock)
        if run_id is None:
            return False

        self._in_flight.add(run_id)
        run = get_run(self.conn, run_id)
        try:
            if run is None:
                raise LookupError(f"run {run_id} disappeared after it was claimed")
            handler = self.handlers.get(run.workflow)
            if handler is None:
                raise LookupError(f"no handler for workflow '{run.workflow}'")
            handler(run_id, run.input)
        except RetryLater as exc:
            # Park the run until the backoff elapses. From now on the claim query
            # skips it, so the lease is no longer needed as a timer.
            schedule(self.conn, run_id, self.clock() + exc.ready_in, clock=self.clock)
        except ApprovalRequired:
            set_status(self.conn, run_id, SUSPENDED, clock=self.clock)
        except BudgetExceeded:
            set_status(self.conn, run_id, CANCELED, clock=self.clock)
        except Exception:
            set_status(self.conn, run_id, FAILED, clock=self.clock)
            raise
        else:
            set_status(self.conn, run_id, COMPLETED, clock=self.clock)
        finally:
            self._in_flight.discard(run_id)
            release(self.conn, run_id, self.worker_id)
        return True

    def drain(self, max_runs: int = 100) -> int:
        """Execute until the queue is empty. Returns how many runs were executed.

        Stops at the first failing run and lets the exception through. A worker
        that should survive bad runs loops over :meth:`poll_once` itself and
        catches - which is exactly what the HTTP poller does.
        """
        executed = 0
        while executed < max_runs and self.poll_once():
            executed += 1
        return executed

    def sweep(self) -> list:
        """Close overdue approvals. Returns the ones that expired."""
        return expire_stale(self.conn, clock=self.clock)

    def close(self) -> None:
        self._stop.set()
        for thread in (self._thread, self._sweep_thread):
            if thread is not None:
                thread.join(timeout=2)

    def __enter__(self) -> "Worker":
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()

    def _ensure_threads(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(
                target=self._heartbeat_loop,
                name=f"duragent-heartbeat-{self.worker_id}",
                daemon=True,
            )
            self._thread.start()

        if self.sweep_every and self._sweep_thread is None:
            self._sweep_thread = threading.Thread(
                target=self._sweep_loop,
                name=f"duragent-sweep-{self.worker_id}",
                daemon=True,
            )
            self._sweep_thread.start()

    def _heartbeat_loop(self) -> None:
        while not self._stop.wait(self.heartbeat_every):
            for run_id in list(self._in_flight):
                try:
                    heartbeat(self.conn, run_id, self.worker_id,
                              lease=self.lease, clock=self.clock)
                except Exception:  # a failed heartbeat must never kill the worker
                    pass

    def _sweep_loop(self) -> None:
        while not self._stop.wait(self.sweep_every):
            try:
                self.sweep()
            except Exception:  # the broom must not take the worker down
                pass
