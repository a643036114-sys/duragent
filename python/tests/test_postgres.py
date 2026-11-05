"""The same contract, on PostgreSQL.

Skipped unless a database is supplied:

    DURAGENT_PG_URL=postgresql://postgres:secret@127.0.0.1:5432/duragent pytest -q
"""

from __future__ import annotations

import os
import time

import pytest

from duragent import (
    COMPLETED,
    ApprovalRequired,
    Journal,
    approve,
    charge,
    claim,
    connect,
    enqueue,
    find_step,
    get_run,
    pending_approvals,
    run_step,
    set_budget,
    usage,
)

PG_URL = os.environ.get("DURAGENT_PG_URL")

pytestmark = pytest.mark.skipif(
    not PG_URL, reason="set DURAGENT_PG_URL to run the PostgreSQL tests"
)

TABLES = ("step_record", "approval", "budget", "run_lease", "run")


@pytest.fixture
def conn():
    connection = connect(PG_URL)
    assert connection.backend_name == "postgres"
    for table in TABLES:
        connection.execute(f"DELETE FROM {table}")
    yield connection
    connection.close()


def test_a_step_replays_across_connections(conn):
    first = run_step(conn, "pg-run", "plan", lambda: {"answer": 42})

    # A different connection = a different process, as far as the database knows.
    other = connect(PG_URL)
    try:
        second = run_step(other, "pg-run", "plan", lambda: {"answer": 43})
    finally:
        other.close()

    assert first == second == {"answer": 42}
    assert find_step(conn, "pg-run", "plan").attempt == 1


def test_a_failed_step_is_retried(conn):
    def boom():
        raise ValueError("529 overloaded")

    with pytest.raises(ValueError):
        run_step(conn, "pg-run", "call", boom)

    assert find_step(conn, "pg-run", "call").status == "FAILED"
    assert run_step(conn, "pg-run", "call", lambda: "ok") == "ok"
    assert find_step(conn, "pg-run", "call").attempt == 2


def test_an_approval_parks_and_then_releases_the_step(conn):
    journal = Journal(conn, "pg-gate")

    def node_fn():
        return "refunded"

    gated = journal.durable("refund", lambda state: node_fn(), needs_approval=True, reason="r")

    with pytest.raises(ApprovalRequired):
        gated({})
    assert find_step(conn, "pg-gate", "refund") is None, "an abort leaves no row"

    approval = pending_approvals(conn, "pg-gate")[0]
    approve(conn, approval.id, by="manager")

    assert gated({}) == "refunded"


def test_a_budget_stops_at_the_cap(conn):
    from duragent import BudgetExceeded

    set_budget(conn, "pg-budget", max_cost=0.05)
    charge(conn, "pg-budget", cost=0.03)
    charge(conn, "pg-budget", cost=0.02)

    with pytest.raises(BudgetExceeded):
        charge(conn, "pg-budget", cost=0.01)

    assert usage(conn, "pg-budget").cost == pytest.approx(0.05)


def test_only_one_worker_claims_a_run(conn):
    run_id = enqueue(conn, "summary", {"topic": "x"})

    assert claim(conn, "worker-a", lease=30) == run_id
    assert claim(conn, "worker-b", lease=30) is None

    time.sleep(0.05)
    assert claim(conn, "worker-c", lease=30) is None, "the lease is still alive"


def test_a_run_can_be_finished_and_is_then_not_claimable(conn):
    from duragent import set_status

    run_id = enqueue(conn, "summary")
    claim(conn, "worker-a", lease=30)
    set_status(conn, run_id, COMPLETED)

    assert claim(conn, "worker-b", lease=30) is None
    assert get_run(conn, run_id).status == COMPLETED
