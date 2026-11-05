"""Human approvals.

A node that moves money, deletes data or emails a customer should not run on its
own. Marking it ``needs_approval`` makes the run stop *before* the side effect,
with the process free to exit; a human decides later.

    journal.durable("refund", refund_fn, needs_approval=True, reason="refund 500 CNY")

An approval may carry a deadline. Without one it waits forever, which is a leak:
the run sits in ``SUSPENDED`` and nothing ever moves it. With one, either the
sweep (:func:`expire_stale`) or the next worker (:func:`require_approval`) turns
it into a terminal decision.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Callable

from duragent.runs import FAILED, RUNNING, SUSPENDED, get_run, set_status
from duragent.steps import Abort

PENDING = "PENDING"
APPROVED = "APPROVED"
REJECTED = "REJECTED"
EXPIRED = "EXPIRED"


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


class ApprovalExpired(Abort):
    """Nobody decided in time. Treated like a rejection: the action must not run."""

    def __init__(self, run_id: str, node: str, requested_at: float):
        super().__init__(f"approval for {run_id}/{node} expired (requested at {requested_at:.0f})")
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
    expires_at: float | None = None

    def is_overdue(self, now: float) -> bool:
        return self.status == PENDING and self.expires_at is not None and now >= self.expires_at


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
                     *, expires_in: float | None = None,
                     clock: Callable[[], float] = time.time) -> Approval:
    """Create a PENDING request, or return the one already waiting.

    ``expires_in`` is in seconds. ``None`` means the request never expires.
    """
    existing = latest_approval(conn, run_id, node)
    if existing is not None and existing.status == PENDING:
        return existing

    now = clock()
    approval_id = str(uuid.uuid4())
    expires_at = None if expires_in is None else now + expires_in
    conn.execute(
        "INSERT INTO approval (id, run_id, node, reason, status, requested_at, expires_at)"
        " VALUES (?, ?, ?, ?, 'PENDING', ?, ?)",
        (approval_id, run_id, node, reason, now, expires_at),
    )
    return latest_approval(conn, run_id, node)  # type: ignore[return-value]


def approve(conn, approval_id: str, by: str, comment: str | None = None,
            *, clock: Callable[[], float] = time.time) -> Approval:
    return _decide(conn, approval_id, APPROVED, by, comment, clock)


def reject(conn, approval_id: str, by: str, comment: str | None = None,
           *, clock: Callable[[], float] = time.time) -> Approval:
    return _decide(conn, approval_id, REJECTED, by, comment, clock)


def require_approval(conn, run_id: str, node: str, reason: str | None = None,
                     *, expires_in: float | None = None,
                     clock: Callable[[], float] = time.time) -> None:
    """Gate called by a durable node right before it executes.

    Returns normally only when a human already approved. Otherwise it raises,
    and the caller of ``graph.invoke`` decides what to tell the user.
    """
    latest = latest_approval(conn, run_id, node)
    if latest is not None:
        if latest.is_overdue(clock()):
            _expire_one(conn, latest, clock)
            raise ApprovalExpired(run_id, node, latest.requested_at)
        if latest.status == APPROVED:
            return
        if latest.status == REJECTED:
            raise ApprovalRejected(run_id, node, latest.decided_by, latest.comment)
        if latest.status == EXPIRED:
            raise ApprovalExpired(run_id, node, latest.requested_at)
        if latest.status == PENDING:
            raise ApprovalRequired(latest.id, run_id, node, latest.reason)

    requested = request_approval(conn, run_id, node, reason, expires_in=expires_in, clock=clock)
    raise ApprovalRequired(requested.id, run_id, node, requested.reason)


def expire_stale(conn, *, now: float | None = None,
                 clock: Callable[[], float] = time.time,
                 fail_run: bool = True) -> list[Approval]:
    """Sweep overdue PENDING approvals.

    This is what stops a run from waiting forever when nobody ever opens the
    approval queue: the sweep is the operator's broom. Returns the approvals it
    actually transitioned.

    ``fail_run=False`` only marks the approvals, leaving runs suspended for a
    human to look at.
    """
    moment = clock() if now is None else now
    rows = conn.execute(
        "SELECT * FROM approval WHERE status = 'PENDING'"
        " AND expires_at IS NOT NULL AND expires_at <= ?",
        (moment,),
    ).fetchall()

    expired: list[Approval] = []
    for row in rows:
        approval = _to_approval(row)
        if approval is None:
            continue
        if _expire_one(conn, approval, clock, moment=moment, fail_run=fail_run):
            refreshed = _find(conn, approval.id)
            if refreshed is not None:
                expired.append(refreshed)
    return expired


def _expire_one(conn, approval: Approval, clock: Callable[[], float],
                *, moment: float | None = None, fail_run: bool = True) -> bool:
    """Move one approval to EXPIRED. False when someone else got there first."""
    now = clock() if moment is None else moment
    won = conn.execute(
        "UPDATE approval SET status = 'EXPIRED', decided_at = ?"
        " WHERE id = ? AND status = 'PENDING'",
        (now, approval.id),
    ).rowcount > 0
    if not won:
        return False

    if fail_run:
        run = get_run(conn, approval.run_id)
        if run is not None and run.status == SUSPENDED:
            set_status(conn, approval.run_id, FAILED, clock=clock)
    return True


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
        expires_at=row["expires_at"],
    )
