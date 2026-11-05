"""Human approvals.

A node that moves money, deletes data or emails a customer should not run on its
own. Marking it ``needs_approval`` makes the run stop *before* the side effect,
with the process free to exit; a human decides later.

    journal.durable("refund", refund_fn, needs_approval=True, reason="refund 500 CNY")
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Callable

from duragent.runs import RUNNING, SUSPENDED, get_run, set_status
from duragent.steps import Abort

PENDING = "PENDING"
APPROVED = "APPROVED"
REJECTED = "REJECTED"


class ApprovalRequired(Abort):
    """A human has not decided yet. The run is parked, not failed."""

    def __init__(self, approval_id: str, run_id: str, node: str, reason: str | None):
        super().__init__(f"approval required for {run_id}/{node}: {reason}")
        self.approval_id = approval_id
        self.run_id = run_id
        self.node = node


class ApprovalRejected(Abort):
    """A human said no. The run must stop; retrying is pointless."""

    def __init__(self, run_id: str, node: str, decided_by: str | None, comment: str | None):
        super().__init__(f"approval rejected for {run_id}/{node} by {decided_by}: {comment}")
        self.run_id = run_id
        self.node = node


@dataclass(frozen=True)
class Approval:
    id: str
    run_id: str
    node: str
    reason: str | None
    status: str
    requested_at: float
    decided_at: float | None
    decided_by: str | None
    comment: str | None


def latest_approval(conn, run_id: str, node: str) -> Approval | None:
    row = conn.execute(
        "SELECT * FROM approval WHERE run_id = ? AND node = ?"
        " ORDER BY requested_at DESC LIMIT 1",
        (run_id, node),
    ).fetchone()
    return _to_approval(row)


def pending_approvals(conn, run_id: str) -> list[Approval]:
    rows = conn.execute(
        "SELECT * FROM approval WHERE run_id = ? AND status = 'PENDING' ORDER BY requested_at",
        (run_id,),
    ).fetchall()
    return [a for a in (_to_approval(r) for r in rows) if a is not None]


def request_approval(conn, run_id: str, node: str, reason: str | None = None,
                     *, clock: Callable[[], float] = time.time) -> Approval:
    """Create a PENDING request, or return the one already waiting."""
    existing = latest_approval(conn, run_id, node)
    if existing is not None and existing.status == PENDING:
        return existing

    approval_id = str(uuid.uuid4())
    conn.execute(
        "INSERT INTO approval (id, run_id, node, reason, status, requested_at)"
        " VALUES (?, ?, ?, ?, 'PENDING', ?)",
        (approval_id, run_id, node, reason, clock()),
    )
    return latest_approval(conn, run_id, node)  # type: ignore[return-value]


def approve(conn, approval_id: str, by: str, comment: str | None = None,
            *, clock: Callable[[], float] = time.time) -> Approval:
    return _decide(conn, approval_id, APPROVED, by, comment, clock)


def reject(conn, approval_id: str, by: str, comment: str | None = None,
           *, clock: Callable[[], float] = time.time) -> Approval:
    return _decide(conn, approval_id, REJECTED, by, comment, clock)


def require_approval(conn, run_id: str, node: str, reason: str | None = None,
                     *, clock: Callable[[], float] = time.time) -> None:
    """Gate called by a durable node right before it executes.

    Returns normally only when a human already approved. Otherwise it raises,
    and the caller of ``graph.invoke`` decides what to tell the user.
    """
    latest = latest_approval(conn, run_id, node)
    if latest is not None:
        if latest.status == APPROVED:
            return
        if latest.status == REJECTED:
            raise ApprovalRejected(run_id, node, latest.decided_by, latest.comment)
        if latest.status == PENDING:
            raise ApprovalRequired(latest.id, run_id, node, latest.reason)

    requested = request_approval(conn, run_id, node, reason)
    raise ApprovalRequired(requested.id, run_id, node, requested.reason)


def _decide(conn, approval_id: str, status: str, by: str, comment: str | None,
            clock: Callable[[], float]) -> Approval:
    # Conditional update: the first decision wins. A second click changes nothing.
    conn.execute(
        "UPDATE approval SET status = ?, decided_by = ?, comment = ?, decided_at = ?"
        " WHERE id = ? AND status = 'PENDING'",
        (status, by, comment, clock(), approval_id),
    )
    approval = _find(conn, approval_id)
    if approval is None:
        raise KeyError(f"unknown approval: {approval_id}")

    # A decision un-parks the run: a worker can pick it up again and the gate
    # will let it through (approved) or raise (rejected).
    run = get_run(conn, approval.run_id)
    if run is not None and run.status == SUSPENDED:
        set_status(conn, approval.run_id, RUNNING, clock=clock)

    return approval


def _find(conn, approval_id: str) -> Approval | None:
    row = conn.execute("SELECT * FROM approval WHERE id = ?", (approval_id,)).fetchone()
    return _to_approval(row)


def _to_approval(row) -> Approval | None:
    if row is None:
        return None
    return Approval(
        id=row["id"],
        run_id=row["run_id"],
        node=row["node"],
        reason=row["reason"],
        status=row["status"],
        requested_at=row["requested_at"],
        decided_at=row["decided_at"],
        decided_by=row["decided_by"],
        comment=row["comment"],
    )
