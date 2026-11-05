"""The durable step: run a function once per (run, step), replay its result forever.

Read this file top to bottom and you have read the project. Everything else
(approvals, budgets, workers) is the same pattern applied to other questions.
"""

from __future__ import annotations

import json
import sqlite3
import time
from dataclasses import dataclass
from typing import Any, Callable, TypeVar

T = TypeVar("T")

DEFAULT_LEASE_SECONDS = 120.0


class StepInProgress(RuntimeError):
    """Another worker owns this step right now. Not an error: just not your turn."""


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


def find_step(conn: sqlite3.Connection, run_id: str, step_name: str) -> StepRecord | None:
    """Read the journal entry for this step, if it exists."""
    row = conn.execute(
        "SELECT * FROM step_record WHERE run_id = ? AND step_key = ?",
        (run_id, step_name),
    ).fetchone()
    if row is None:
        return None
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
    )


def run_step(
    conn: sqlite3.Connection,
    run_id: str,
    step_name: str,
    fn: Callable[[], T],
    *,
    lease: float = DEFAULT_LEASE_SECONDS,
    clock: Callable[[], float] = time.time,
) -> T:
    """Run ``fn`` at most once per ``(run_id, step_name)``.

    * first call  -> claim the step, run ``fn``, store the result
    * later calls -> return the stored result, ``fn`` is never called
    * a failure   -> recorded as FAILED, the next call retries it
    * a crash     -> the lease expires, another worker takes the step over

    The result must be JSON-serialisable, because that is what the journal stores.
    """
    existing = find_step(conn, run_id, step_name)
    now = clock()

    if existing is not None and existing.status == "SUCCEEDED":
        return json.loads(existing.output_json)  # replay: the whole point

    lease_until = now + lease
    if existing is None:
        try:
            conn.execute(
                "INSERT INTO step_record (run_id, step_key, status, attempt, started_at, lease_until)"
                " VALUES (?, ?, 'RUNNING', 1, ?, ?)",
                (run_id, step_name, now, lease_until),
            )
        except sqlite3.IntegrityError:
            # Another worker inserted between our read and our write.
            return _replay_or_raise(conn, run_id, step_name)
    elif existing.status == "RUNNING":
        if existing.lease_until > now:
            raise StepInProgress(f"{run_id}/{step_name} is already running in another worker")
        _claim(conn, "status = 'RUNNING' AND lease_until < ?", run_id, step_name, now, lease_until, now)
    else:  # FAILED
        _claim(conn, "status = 'FAILED'", run_id, step_name, now, lease_until)

    try:
        result = fn()
    except Exception as exc:
        conn.execute(
            "UPDATE step_record SET status = 'FAILED', error_text = ?, finished_at = ?"
            " WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'",
            (f"{type(exc).__name__}: {exc}", clock(), run_id, step_name),
        )
        raise

    # The side effect already happened; from here on we only record it.
    conn.execute(
        "UPDATE step_record SET status = 'SUCCEEDED', output_json = ?, finished_at = ?"
        " WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'",
        (json.dumps(result, ensure_ascii=False, sort_keys=True), clock(), run_id, step_name),
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


def _replay_or_raise(conn: sqlite3.Connection, run_id: str, step_name: str) -> Any:
    """Someone beat us to the insert: replay their result if they already finished."""
    record = find_step(conn, run_id, step_name)
    if record is not None and record.status == "SUCCEEDED":
        return json.loads(record.output_json)
    raise StepInProgress(f"{run_id}/{step_name} is already running in another worker")
