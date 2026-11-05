"""Runs and their execution leases.

A run is one execution of a workflow. A lease says "worker X is allowed to
execute run Y until time T" - which is the only thing standing between a crashed
worker and a run that is never picked up again.

Claiming is two steps on purpose:

1. list candidates  (PENDING, or RUNNING with no live lease)
2. try to take the lease for one of them

Two workers may list the same candidate; the lease decides. Losing that race is
normal, so the caller just moves to the next candidate.
"""

from __future__ import annotations

import json
import sqlite3
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable

PENDING = "PENDING"
RUNNING = "RUNNING"
SUSPENDED = "SUSPENDED"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
CANCELED = "CANCELED"


@dataclass(frozen=True)
class Run:
    id: str
    workflow: str
    status: str
    input: dict[str, Any]
    created_at: float
    updated_at: float


def enqueue(conn, workflow: str, payload: dict[str, Any] | None = None,
            *, clock: Callable[[], float] = time.time) -> str:
    """Create a run that waits for a worker instead of running immediately."""
    run_id = str(uuid.uuid4())
    now = clock()
    conn.execute(
        "INSERT INTO run (id, workflow, status, input_json, created_at, updated_at)"
        " VALUES (?, ?, 'PENDING', ?, ?, ?)",
        (run_id, workflow, json.dumps(payload or {}, ensure_ascii=False), now, now),
    )
    return run_id


def get_run(conn, run_id: str) -> Run | None:
    row = conn.execute("SELECT * FROM run WHERE id = ?", (run_id,)).fetchone()
    if row is None:
        return None
    return Run(
        id=row["id"],
        workflow=row["workflow"],
        status=row["status"],
        input=json.loads(row["input_json"] or "{}"),
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


def set_status(conn, run_id: str, status: str, *,
               clock: Callable[[], float] = time.time) -> None:
    conn.execute(
        "UPDATE run SET status = ?, updated_at = ? WHERE id = ?",
        (status, clock(), run_id),
    )


def claim(conn, worker_id: str, *, lease: float = 30.0,
          clock: Callable[[], float] = time.time) -> str | None:
    """Take one run, or return None when there is nothing to do.

    The lease is what arbitrates: two workers can both see the same candidate,
    but only one insert wins the primary key.
    """
    now = clock()
    leased_until = now + lease

    for (run_id,) in _claimable(conn, now):
        if not _try_acquire(conn, run_id, worker_id, leased_until, now):
            continue
        # Promote PENDING -> RUNNING, but only if nobody moved it meanwhile.
        updated = conn.execute(
            "UPDATE run SET status = 'RUNNING', updated_at = ? WHERE id = ? AND status = 'PENDING'",
            (now, run_id),
        ).rowcount
        if updated == 0 and (get_run(conn, run_id) or Run("", "", "", {}, 0, 0)).status != RUNNING:
            release(conn, run_id, worker_id)
            continue
        return run_id
    return None


def heartbeat(conn, run_id: str, worker_id: str, *, lease: float = 30.0,
              clock: Callable[[], float] = time.time) -> bool:
    """Extend the lease. False means another worker has already taken the run."""
    now = clock()
    return conn.execute(
        "UPDATE run_lease SET leased_until = ?, heartbeat_at = ?"
        " WHERE run_id = ? AND worker_id = ?",
        (now + lease, now, run_id, worker_id),
    ).rowcount > 0


def release(conn, run_id: str, worker_id: str) -> bool:
    return conn.execute(
        "DELETE FROM run_lease WHERE run_id = ? AND worker_id = ?",
        (run_id, worker_id),
    ).rowcount > 0


def _claimable(conn, now: float) -> list[tuple[str]]:
    rows = conn.execute(
        "SELECT r.id FROM run r"
        " LEFT JOIN run_lease l ON l.run_id = r.id"
        " WHERE r.status = 'PENDING'"
        "    OR (r.status = 'RUNNING' AND (l.run_id IS NULL OR l.leased_until < ?))"
        " ORDER BY r.created_at LIMIT 10",
        (now,),
    ).fetchall()
    return [(row["id"],) for row in rows]


def _try_acquire(conn, run_id: str, worker_id: str, leased_until: float, now: float) -> bool:
    try:
        conn.execute(
            "INSERT INTO run_lease (run_id, worker_id, leased_until, claimed_at, heartbeat_at)"
            " VALUES (?, ?, ?, ?, ?)",
            (run_id, worker_id, leased_until, now, now),
        )
        return True
    except sqlite3.IntegrityError:
        # A lease exists. Take it over only if it has expired.
        return conn.execute(
            "UPDATE run_lease SET worker_id = ?, leased_until = ?, claimed_at = ?, heartbeat_at = ?"
            " WHERE run_id = ? AND leased_until < ?",
            (worker_id, leased_until, now, now, run_id, now),
        ).rowcount > 0
