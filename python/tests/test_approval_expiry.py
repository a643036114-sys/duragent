"""Approvals with a deadline: the run must not wait forever."""

from __future__ import annotations

from typing import TypedDict

import pytest

from duragent import (
    FAILED,
    SUSPENDED,
    ApprovalExpired,
    ApprovalRequired,
    Journal,
    approve,
    claim,
    connect,
    enqueue,
    expire_stale,
    get_run,
    latest_approval,
    pending_approvals,
    request_approval,
    set_status,
)


class Clock:
    def __init__(self, start: float = 1_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class State(TypedDict, total=False):
    refunded: bool


def test_request_stores_the_deadline():
    conn = connect()
    clock = Clock()

    approval = request_approval(conn, "run-1", "refund", "500 CNY",
                                expires_in=60, clock=clock)

    assert approval.expires_at == pytest.approx(clock.now + 60)


def test_without_a_deadline_nothing_expires():
    conn = connect()
    clock = Clock()

    request_approval(conn, "run-2", "refund", "500 CNY", clock=clock)
    clock.advance(10_000)

    assert expire_stale(conn, clock=clock) == []


def test_the_sweep_expires_overdue_requests_and_fails_the_run():
    conn = connect()
    clock = Clock()
    run_id = enqueue(conn, "refund")
    claim(conn, "worker-a", lease=30)
    set_status(conn, run_id, SUSPENDED)

    request_approval(conn, run_id, "refund", "500 CNY", expires_in=60, clock=clock)
    clock.advance(61)

    expired = expire_stale(conn, clock=clock)

    assert len(expired) == 1
    assert expired[0].status == "EXPIRED", "the sweep returns the updated rows"
    assert latest_approval(conn, run_id, "refund").status == "EXPIRED"
    assert get_run(conn, run_id).status == FAILED, "an expired approval must not leave the run parked"


def test_the_sweep_leaves_fresh_requests_alone():
    conn = connect()
    clock = Clock()
    run_id = enqueue(conn, "refund")
    claim(conn, "worker-a", lease=30)
    set_status(conn, run_id, SUSPENDED)

    request_approval(conn, run_id, "refund", "500 CNY", expires_in=60, clock=clock)
    clock.advance(30)

    assert expire_stale(conn, clock=clock) == []
    assert latest_approval(conn, run_id, "refund").status == "PENDING"
    assert get_run(conn, run_id).status == SUSPENDED


def test_a_worker_reaches_an_overdue_request_before_the_sweep():
    """No sweep is needed: the gate notices the deadline itself."""
    conn = connect()
    clock = Clock()
    journal = Journal(conn, "run-gate", clock=clock)

    node = journal.durable("refund", lambda state: {"refunded": True},
                           needs_approval=True, reason="500 CNY", expires_in=60)

    with pytest.raises(ApprovalRequired):
        node({})

    clock.advance(61)

    with pytest.raises(ApprovalExpired):
        node({})

    assert latest_approval(conn, "run-gate", "refund").status == "EXPIRED"


def test_an_approved_request_is_not_expired_by_the_sweep():
    conn = connect()
    clock = Clock()
    run_id = enqueue(conn, "refund")
    claim(conn, "worker-a", lease=30)
    set_status(conn, run_id, SUSPENDED)

    approval = request_approval(conn, run_id, "refund", "500 CNY", expires_in=60, clock=clock)
    approve(conn, approval.id, by="manager", clock=clock)
    clock.advance(120)

    assert expire_stale(conn, clock=clock) == []
    assert latest_approval(conn, run_id, "refund").status == "APPROVED"


def test_a_database_from_before_the_deadline_column_is_upgraded(tmp_path):
    path = str(tmp_path / "old.db")
    conn = connect(path)
    try:
        conn.execute("ALTER TABLE approval DROP COLUMN expires_at")
    except Exception:  # SQLite older than 3.35 cannot drop a column
        pytest.skip("this SQLite cannot drop a column")
    conn.close()

    upgraded = connect(path)

    upgraded.execute("SELECT expires_at FROM approval WHERE 1 = 0")  # must not raise
    request_approval(upgraded, "run-old", "refund", "x", expires_in=5)
    assert pending_approvals(upgraded, "run-old")[0].expires_at is not None
