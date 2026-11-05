"""Backoff: do not hammer a failing dependency, and give up eventually."""

from __future__ import annotations

import pytest

from duragent import (
    RetriesExhausted,
    Retry,
    RetryLater,
    Worker,
    claim,
    connect,
    enqueue,
    find_step,
    get_run,
    run_step,
)


class Clock:
    def __init__(self, start: float = 1_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_the_delay_grows_exponentially_and_is_capped():
    policy = Retry(base=1.0, multiplier=2.0, max_delay=10.0)

    assert [policy.delay_for(n) for n in range(1, 7)] == [1.0, 2.0, 4.0, 8.0, 10.0, 10.0]


def test_a_recent_failure_backs_off_instead_of_retrying_at_once():
    conn = connect()
    clock = Clock()
    calls = []
    policy = Retry(max_attempts=3, base=2.0)

    def flaky():
        calls.append(1)
        raise ValueError("529 overloaded")

    with pytest.raises(ValueError):
        run_step(conn, "run-1", "call", flaky, retry=policy, clock=clock)

    clock.advance(0.5)
    with pytest.raises(RetryLater) as caught:
        run_step(conn, "run-1", "call", flaky, retry=policy, clock=clock)

    assert caught.value.ready_in == pytest.approx(1.5)
    assert len(calls) == 1, "the body must not run during the backoff"

    clock.advance(1.5)   # 2.0s since the failure
    assert run_step(conn, "run-1", "call", lambda: "ok", retry=policy, clock=clock) == "ok"
    assert find_step(conn, "run-1", "call").attempt == 2


def test_retries_are_capped():
    conn = connect()
    clock = Clock()
    policy = Retry(max_attempts=2, base=0.0)

    def flaky():
        raise ValueError("still broken")

    with pytest.raises(ValueError):
        run_step(conn, "run-2", "call", flaky, retry=policy, clock=clock)
    with pytest.raises(ValueError):
        run_step(conn, "run-2", "call", flaky, retry=policy, clock=clock)

    calls = []
    with pytest.raises(RetriesExhausted) as caught:
        run_step(conn, "run-2", "call", lambda: calls.append(1), retry=policy, clock=clock)

    assert caught.value.attempts == 2
    assert calls == [], "an exhausted step must not execute again"


def test_without_a_policy_a_failure_retries_immediately():
    conn = connect()
    calls = []

    def flaky():
        calls.append(1)
        raise ValueError("boom")

    with pytest.raises(ValueError):
        run_step(conn, "run-3", "call", flaky)

    with pytest.raises(ValueError):
        run_step(conn, "run-3", "call", flaky)

    assert len(calls) == 2, "the default stays the old behaviour: retry on the next call"


def test_a_backing_off_run_stays_running_and_is_not_immediately_claimable():
    conn = connect()
    run_id = enqueue(conn, "flaky")

    def handler(rid, payload):
        raise RetryLater(rid, "call", 5.0)

    with Worker(conn, "worker-a", {"flaky": handler},
                lease=30, heartbeat_every=5, sweep_every=None) as worker:
        assert worker.poll_once()

    run = get_run(conn, run_id)
    assert run.status == "RUNNING", "backing off is not a failure"
    assert run.not_before is not None, "the run is scheduled for later"
    assert claim(conn, "worker-b", lease=30) is None, "and is not claimable yet"
