"""Queue, lease and worker: who gets to execute which run, and for how long."""

from __future__ import annotations

import time

import pytest

from duragent import (
    CANCELED,
    COMPLETED,
    SUSPENDED,
    ApprovalRequired,
    BudgetExceeded,
    Journal,
    Worker,
    claim,
    connect,
    enqueue,
    get_run,
    release,
)


def test_enqueue_then_claim_promotes_the_run():
    conn = connect()
    run_id = enqueue(conn, "summary", {"topic": "x"})
    assert get_run(conn, run_id).status == "PENDING"

    claimed = claim(conn, "worker-a", lease=30)

    assert claimed == run_id
    assert get_run(conn, run_id).status == "RUNNING"


def test_a_live_lease_keeps_other_workers_out():
    conn = connect()
    enqueue(conn, "summary")

    assert claim(conn, "worker-a", lease=30) is not None
    assert claim(conn, "worker-b", lease=30) is None


def test_an_expired_lease_is_taken_over():
    conn = connect()
    run_id = enqueue(conn, "summary")
    assert claim(conn, "worker-a", lease=0.05) == run_id

    time.sleep(0.1)

    assert claim(conn, "worker-b", lease=30) == run_id


def test_release_hands_the_run_to_the_next_worker():
    conn = connect()
    run_id = enqueue(conn, "summary")
    claim(conn, "worker-a", lease=60)

    release(conn, run_id, "worker-a")

    assert claim(conn, "worker-b", lease=60) == run_id


def test_the_worker_executes_the_handler_and_closes_the_run():
    conn = connect()
    run_id = enqueue(conn, "summary", {"topic": "x"})
    seen = []

    def handler(rid, payload):
        seen.append((rid, payload))

    with Worker(conn, "worker-a", {"summary": handler}, lease=10, heartbeat_every=2) as worker:
        assert worker.poll_once()

    assert seen == [(run_id, {"topic": "x"})]
    assert get_run(conn, run_id).status == COMPLETED


def test_a_paused_handler_marks_the_run_suspended_not_failed():
    conn = connect()
    run_id = enqueue(conn, "refund")

    def handler(rid, payload):
        raise ApprovalRequired("appr-1", rid, "refund", "needs a manager")

    with Worker(conn, "worker-a", {"refund": handler}, lease=10, heartbeat_every=2) as worker:
        assert worker.poll_once()

    assert get_run(conn, run_id).status == SUSPENDED


def test_a_budget_stop_marks_the_run_canceled():
    conn = connect()
    run_id = enqueue(conn, "burn")

    def handler(rid, payload):
        raise BudgetExceeded(rid, "cost", 100, 200)

    with Worker(conn, "worker-a", {"burn": handler}, lease=10, heartbeat_every=2) as worker:
        assert worker.poll_once()

    assert get_run(conn, run_id).status == CANCELED


def test_a_failing_handler_marks_the_run_failed_and_releases_the_lease():
    conn = connect()
    run_id = enqueue(conn, "broken")

    def handler(rid, payload):
        raise RuntimeError("boom")

    with Worker(conn, "worker-a", {"broken": handler}, lease=10, heartbeat_every=2) as worker:
        with pytest.raises(RuntimeError):
            worker.poll_once()

    assert get_run(conn, run_id).status == "FAILED"
    assert claim(conn, "worker-b", lease=10) is None, "a finished run is not claimable"


def test_two_workers_never_execute_the_same_run():
    conn = connect()
    run_id = enqueue(conn, "summary")
    executed_by = []

    def handler(rid, payload):
        time.sleep(0.15)
        executed_by.append(rid)

    with Worker(conn, "worker-a", {"summary": handler}, lease=30, heartbeat_every=5) as a, \
            Worker(conn, "worker-b", {"summary": handler}, lease=30, heartbeat_every=5) as b:
        assert a.poll_once() or b.poll_once()

    assert executed_by == [run_id]
    assert get_run(conn, run_id).status == COMPLETED


def test_a_worker_with_no_handler_fails_the_run_instead_of_hanging():
    conn = connect()
    run_id = enqueue(conn, "unknown-workflow")

    with Worker(conn, "worker-a", {}, lease=10, heartbeat_every=2) as worker:
        with pytest.raises(LookupError):
            worker.poll_once()

    assert get_run(conn, run_id).status == "FAILED"


def test_the_queue_drains_several_runs():
    conn = connect()
    for i in range(3):
        enqueue(conn, "summary", {"i": i})

    with Worker(conn, "worker-a", {"summary": lambda rid, p: None},
                lease=10, heartbeat_every=2) as worker:
        assert worker.drain() == 3
        assert worker.poll_once() is False
