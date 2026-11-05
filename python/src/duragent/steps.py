"""The durable step: run a function once per (run, step), replay its result forever.

Read this file top to bottom and you have read the project. Everything else
(approvals, budgets, workers) is the same pattern applied to other questions.
"""

from __future__ import annotations

import json
from duragent.db import Connection, IntegrityError
from duragent.retry import RetriesExhausted, Retry, RetryLater
import time
from dataclasses import dataclass
from typing import Any, Callable, TypeVar

T = TypeVar("T")

DEFAULT_LEASE_SECONDS = 120.0


class StepInProgress(RuntimeError):
    """Another worker owns this step right now. Not an error: just not your turn."""


class Abort(RuntimeError):
    """Control flow, not failure.

    A step that was stopped by a guardrail (a pending approval, an exhausted
    budget) never produced a result. Journaling it as FAILED would make it look
    retryable and a later resume would silently skip the work it never did, so
    the claim is released instead - as if the step had never started.
    """


class InputChanged(RuntimeError):
    """The step already ran, but on a different input.

    This is the "I tweaked the prompt and the answer did not change" bug. The
    journal is keyed by step name, so a replay happily returns the old result;
    fingerprinting the input turns that silent surprise into an error.

    Start a new run id when you actually want new work.
    """

    def __init__(self, run_id: str, step_name: str):
        super().__init__(
            f"{run_id}/{step_name} already ran on a different input; "
            "use a new run id to execute it again"
        )
        self.run_id = run_id
        self.step_name = step_name


@dataclass(frozen=True)
class StepRecord:
    """One row of the journal."""

    run_id: str
    step_key: str
    status: str  # RUNNING | SUCCEEDED | FAILED
    attempt: int
    output_json: str | None
    error_text: str | None
    started_at: float
    finished_at: float | None
    lease_until: float
    input_hash: str | None = None


def find_step(conn: Connection, run_id: str, step_name: str) -> StepRecord | None:
    """Read the journal entry for this step, if it exists."""
    row = conn.execute(
        "SELECT * FROM step_record WHERE run_id = ? AND step_key = ?",
        (run_id, step_name),
    ).fetchone()
    return None if row is None else _to_record(row)


def list_steps(conn: Connection, run_id: str) -> list[StepRecord]:
    """Every journal entry of a run, in the order the steps started."""
    rows = conn.execute(
        "SELECT * FROM step_record WHERE run_id = ? ORDER BY started_at, step_key",
        (run_id,),
    ).fetchall()
    return [_to_record(row) for row in rows]


def _to_record(row) -> StepRecord:
    return StepRecord(
        run_id=row["run_id"],
        step_key=row["step_key"],
        status=row["status"],
        attempt=row["attempt"],
        output_json=row["output_json"],
        error_text=row["error_text"],
        started_at=row["started_at"],
        finished_at=row["finished_at"],
        lease_until=row["lease_until"],
        input_hash=row["input_hash"],
    )


def run_step(
    conn: Connection,
    run_id: str,
    step_name: str,
    fn: Callable[[], T],
    *,
    before_execute: Callable[[], None] | None = None,
    retry: Retry | None = None,
    fingerprint: str | None = None,
    lease: float = DEFAULT_LEASE_SECONDS,
    clock: Callable[[], float] = time.time,
) -> T:
    """Run ``fn`` at most once per ``(run_id, step_name)``.

    * first call  -> claim the step, run ``fn``, store the result
    * later calls -> return the stored result, ``fn`` is never called
    * a failure   -> recorded as FAILED, retried according to ``retry``
    * an abort    -> the claim is released, as if the step never started
    * a crash     -> the lease expires, another worker takes the step over

    ``before_execute`` is the gate: it runs only when the step is really about to
    execute (never on a replay), and raising from it aborts the step.

    ``retry`` turns a recent failure into :class:`RetryLater` instead of running
    again immediately, and :class:`RetriesExhausted` once the attempts are used up.

    ``fingerprint`` is an opaque hash of the step's input. When given, a replay
    whose fingerprint differs raises :class:`InputChanged` instead of returning a
    result that belongs to a different input.
    """
    if not isinstance(step_name, str) or not step_name.strip():
        raise ValueError("step_name must be a non-empty string")

    existing = find_step(conn, run_id, step_name)
    now = clock()
    if existing is not None and existing.status == "SUCCEEDED":
        if fingerprint is not None and existing.input_hash not in (None, fingerprint):
            raise InputChanged(run_id, step_name)
        return json.loads(existing.output_json)  # replay: the whole point

    lease_until = now + lease
    if existing is None:
        try:
            conn.execute(
                "INSERT INTO step_record"
                " (run_id, step_key, status, attempt, started_at, lease_until, input_hash)"
                " VALUES (?, ?, 'RUNNING', 1, ?, ?, ?)",
                (run_id, step_name, now, lease_until, fingerprint),
            )
        except IntegrityError:
            # Another worker inserted between our read and our write.
            return _replay_or_raise(conn, run_id, step_name)
    elif existing.status == "RUNNING":
        if existing.lease_until > now:
            raise StepInProgress(f"{run_id}/{step_name} is already running in another worker")
        _claim(conn, "status = 'RUNNING' AND lease_until < ?", run_id, step_name, now, lease_until, now)
    else:  # FAILED
        if retry is not None:
            if existing.attempt >= retry.max_attempts:
                raise RetriesExhausted(run_id, step_name, existing.attempt, existing.error_text)
            ready_at = (existing.finished_at or 0.0) + retry.delay_for(existing.attempt)
            if now < ready_at:
                raise RetryLater(run_id, step_name, ready_at - now)
        _claim(conn, "status = 'FAILED'", run_id, step_name, now, lease_until)

    try:
        if before_execute is not None:
            before_execute()
        result = fn()
    except Abort:
        conn.execute(
            "DELETE FROM step_record WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'",
            (run_id, step_name),
        )
        raise
    except Exception as exc:
        conn.execute(
            "UPDATE step_record SET status = 'FAILED', error_text = ?, finished_at = ?"
            " WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'",
            (f"{type(exc).__name__}: {exc}", clock(), run_id, step_name),
        )
        raise

    # The side effect already happened; from here on we only record it.
    try:
        output_json = json.dumps(result, ensure_ascii=False, sort_keys=True)
    except TypeError as exc:
        # The work is done but cannot be described in the journal. Failing the step is
        # the honest choice: it keeps a trace, and a retry re-runs it deliberately.
        conn.execute(
            "UPDATE step_record SET status = 'FAILED', error_text = ?, finished_at = ?"
            " WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'",
            (f"TypeError: the step result is not JSON-serialisable: {exc}",
             clock(), run_id, step_name),
        )
        raise TypeError(
            f"step {step_name!r} returned a value that cannot be stored as JSON, "
            f"so a replay would not be able to restore it: {exc}"
        ) from exc

    conn.execute(
        "UPDATE step_record SET status = 'SUCCEEDED', output_json = ?, finished_at = ?"
        " WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'",
        (output_json, clock(), run_id, step_name),
    )
    return result


def _claim(conn, condition: str, run_id: str, step_name: str, now: float,
           lease_until: float, *extra: Any) -> None:
    """Take over an expired claim or retry a failed one. Row count says whether we won."""
    updated = conn.execute(
        "UPDATE step_record SET status = 'RUNNING', attempt = attempt + 1,"
        f" started_at = ?, lease_until = ?, finished_at = NULL, error_text = NULL"
        f" WHERE run_id = ? AND step_key = ? AND {condition}",
        (now, lease_until, run_id, step_name, *extra),
    ).rowcount
    if updated == 0:
        raise StepInProgress(f"{run_id}/{step_name} was taken by another worker")


def _replay_or_raise(conn: Connection, run_id: str, step_name: str) -> Any:
    """Someone beat us to the insert: replay their result if they already finished."""
    record = find_step(conn, run_id, step_name)
    if record is not None and record.status == "SUCCEEDED":
        return json.loads(record.output_json)
    raise StepInProgress(f"{run_id}/{step_name} is already running in another worker")
