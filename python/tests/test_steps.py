"""The four behaviours that make the journal worth having."""

from __future__ import annotations

import pytest

from duragent import StepInProgress, connect, find_step, run_step


def test_the_function_runs_once():
    conn = connect()
    calls = []

    def work():
        calls.append(1)
        return {"answer": 42}

    first = run_step(conn, "run-1", "plan", work)
    second = run_step(conn, "run-1", "plan", work)

    assert first == second == {"answer": 42}
    assert len(calls) == 1, "the second call must replay, not execute"


def test_a_finished_step_replays_its_stored_value_not_a_fresh_one():
    conn = connect()
    run_step(conn, "run-2", "plan", lambda: "first answer")

    replayed = run_step(conn, "run-2", "plan", lambda: "this must never be returned")

    assert replayed == "first answer"


def test_a_failure_is_recorded_and_the_next_call_retries():
    conn = connect()
    attempts = []

    def flaky():
        attempts.append(1)
        raise ValueError("529 overloaded")

    with pytest.raises(ValueError):
        run_step(conn, "run-3", "call-llm", flaky)

    failed = find_step(conn, "run-3", "call-llm")
    assert failed.status == "FAILED"
    assert "529" in failed.error_text

    assert run_step(conn, "run-3", "call-llm", lambda: "ok") == "ok"
    assert len(attempts) == 1
    assert find_step(conn, "run-3", "call-llm").attempt == 2


def test_a_live_claim_blocks_a_second_worker():
    conn = connect()

    def nested():
        # A second worker tries the same step while the first one still holds it.
        run_step(conn, "run-4", "charge", lambda: "again")

    with pytest.raises(StepInProgress):
        run_step(conn, "run-4", "charge", nested)


def test_an_expired_lease_is_taken_over():
    conn = connect()
    clock = [1000.0]

    run_step(conn, "run-5", "summarize", lambda: "first", clock=lambda: clock[0], lease=10)

    # Simulate a worker that died mid-step: the row says RUNNING, the lease is stale.
    conn.execute(
        "UPDATE step_record SET status = 'RUNNING', finished_at = NULL, lease_until = ?"
        " WHERE run_id = ? AND step_key = ?",
        (clock[0], "run-5", "summarize"),
    )
    clock[0] += 100

    result = run_step(conn, "run-5", "summarize", lambda: "second", clock=lambda: clock[0])

    assert result == "second", "a dead worker's step must be re-executed"
    assert find_step(conn, "run-5", "summarize").attempt == 2


def test_the_journal_survives_a_restart(tmp_path):
    path = str(tmp_path / "agent.db")
    calls = []

    conn = connect(path)
    run_step(conn, "run-6", "plan", lambda: calls.append(1) or {"v": 1})
    conn.close()

    # A brand new process with a brand new connection.
    conn2 = connect(path)
    result = run_step(conn2, "run-6", "plan", lambda: calls.append(1) or {"v": 2})

    assert result == {"v": 1}
    assert len(calls) == 1
