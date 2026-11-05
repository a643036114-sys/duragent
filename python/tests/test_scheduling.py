"""When a run is allowed to come back: backoff deadlines and the approval broom."""

from __future__ import annotations

import time

import pytest

from duragent import (
    FAILED,
    SUSPENDED,
    RetryLater,
    Worker,
    claim,
    connect,
    enqueue,
    get_run,
    request_approval,
    schedule,
    set_status,
)


class Clock:
    def __init__(self, start: float = 1_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_a_scheduled_run_is_not_claimable_until_its_time():
    conn = connect()
    clock = Clock()
    run_id = enqueue(conn, "summary", clock=clock)

    schedule(conn, run_id, clock.now + 60, clock=clock)

    assert claim(conn, "worker-a", lease=30, clock=clock) is None

    clock.advance(61)
    assert claim(conn, "worker-a", lease=30, clock=clock) == run_id


def test_a_status_change_clears_the_deadline():
    conn = connect()
    clock = Clock()
    run_id = enqueue(conn, "summary", clock=clock)
    schedule(conn, run_id, clock.now + 600, clock=clock)

    set_status(conn, run_id, SUSPENDED, clock=clock)

    assert get_run(conn, run_id).not_before is None
    assert claim(conn, "worker-a", lease=30, clock=clock) is None, "SUSPENDED is still not claimable"


def test_a_backing_off_run_is_scheduled_instead_of_keeping_the_lease():
    conn = connect()
    clock = Clock()
    run_id = enqueue(conn, "flaky", clock=clock)

    def handler(rid, payload):
        raise RetryLater(rid, "call", 30.0)

    with Worker(conn, "worker-a", {"flaky": handler},
                lease=30, heartbeat_every=5, sweep_every=None, clock=clock) as worker:
        assert worker.poll_once()

    run = get_run(conn, run_id)
    assert run.status == "RUNNING", "backing off is not a failure"
    assert run.not_before == pytest.approx(clock.now + 30)

    clock.advance(31)
    assert claim(conn, "worker-b", lease=30, clock=clock) == run_id


def test_the_worker_sweeps_overdue_approvals():
    conn = connect()
    clock = Clock()
    run_id = enqueue(conn, "refund", clock=clock)
    claim(conn, "worker-a", lease=30, clock=clock)
    set_status(conn, run_id, SUSPENDED, clock=clock)
    request_approval(conn, run_id, "refund", "500 CNY", expires_in=10, clock=clock)

    clock.advance(11)

    with Worker(conn, "worker-a", {}, lease=30, heartbeat_every=5,
                sweep_every=None, clock=clock) as worker:
        expired = worker.sweep()

    assert len(expired) == 1
    assert get_run(conn, run_id).status == FAILED


def test_the_sweep_runs_on_its_own_thread():
    conn = connect()
    run_id = enqueue(conn, "refund")
    claim(conn, "worker-a", lease=30)
    set_status(conn, run_id, SUSPENDED)
    request_approval(conn, run_id, "refund", "500 CNY", expires_in=0.05)

    with Worker(conn, "worker-a", {}, lease=30, heartbeat_every=5, sweep_every=0.05) as worker:
        worker.poll_once()          # starts the threads
        time.sleep(0.4)             # the broom should have run

    assert get_run(conn, run_id).status == FAILED, "the background sweep must close overdue approvals"
