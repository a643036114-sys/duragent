"""Adversarial cases: crashes, races, bad input, and the sharp edges we chose.

These are the tests that decide whether the library is trustworthy. Read the
docstrings: several of them document a deliberate limitation rather than a bug.
"""

from __future__ import annotations

import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

from duragent import (
    COMPLETED,
    SUSPENDED,
    ApprovalRequired,
    Journal,
    StepInProgress,
    approve,
    claim,
    connect,
    enqueue,
    find_step,
    get_run,
    request_approval,
    run_step,
    set_status,
)

SRC = str(Path(__file__).resolve().parents[1] / "src")


def test_a_non_serialisable_result_does_not_leave_a_stuck_claim():
    """A step whose result cannot be stored must fail loudly, not hang as RUNNING."""
    conn = connect()

    with pytest.raises(TypeError):
        run_step(conn, "run-bad", "weird", lambda: object())

    record = find_step(conn, "run-bad", "weird")
    assert record is not None
    assert record.status == "FAILED", "the claim must not stay RUNNING forever"
    assert "JSON" in (record.error_text or "")


def test_an_empty_step_name_is_rejected_immediately():
    conn = connect()
    with pytest.raises(ValueError):
        run_step(conn, "run-1", "   ", lambda: 1)


def test_two_threads_racing_for_one_step_execute_it_once():
    conn = connect()
    barrier = threading.Barrier(2)
    executions = []
    outcomes = []

    def work():
        executions.append(1)
        time.sleep(0.2)
        return "done"

    def call():
        barrier.wait()
        try:
            outcomes.append(run_step(conn, "run-race", "charge", work))
        except StepInProgress:
            outcomes.append("in-progress")

    threads = [threading.Thread(target=call) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10)

    assert len(executions) == 1, "only one thread may execute the body"
    assert sorted(outcomes, key=str) == ["done", "in-progress"]


def test_a_hard_crash_mid_step_is_recovered_by_the_lease(tmp_path):
    """kill -9 in the middle of a step: no cleanup, no exception handler."""
    db = tmp_path / "crash.db"
    script = """
import os, sys
sys.path.insert(0, sys.argv[2])
from duragent import connect, run_step

conn = connect(sys.argv[1])

def work():
    with open(sys.argv[1] + ".proof", "w") as fh:
        fh.write("ran")
    os._exit(9)

run_step(conn, "run-crash", "charge", work, lease=0.2)
"""
    proc = subprocess.run(
        [sys.executable, "-c", script, str(db), SRC],
        capture_output=True, text=True, timeout=60,
    )
    assert proc.returncode == 9, proc.stderr
    assert (db.parent / "crash.db.proof").exists(), "the side effect did happen"

    conn = connect(str(db))
    record = find_step(conn, "run-crash", "charge")
    assert record is not None and record.status == "RUNNING", "the dead worker's claim is still there"

    time.sleep(0.3)   # the 0.2s lease expires

    recovered = run_step(conn, "run-crash", "charge", lambda: "charged-again", lease=5)
    assert recovered == "charged-again", "a dead worker's step is re-executed"

    after = find_step(conn, "run-crash", "charge")
    assert after.status == "SUCCEEDED"
    assert after.attempt == 2


def test_a_finished_run_is_not_resurrected_by_a_late_approval():
    conn = connect()
    run_id = enqueue(conn, "refund")
    approval = request_approval(conn, run_id, "refund", "refund 500")

    claim(conn, "worker-a", lease=30)
    set_status(conn, run_id, COMPLETED)

    approve(conn, approval.id, by="manager@corp")

    assert get_run(conn, run_id).status == COMPLETED, "a decision must not re-open a finished run"


def test_a_suspended_run_is_only_reopened_by_a_decision():
    conn = connect()
    run_id = enqueue(conn, "refund")
    claim(conn, "worker-a", lease=30)
    set_status(conn, run_id, SUSPENDED)

    assert claim(conn, "worker-b", lease=30) is None, "nobody may take a run that waits for a human"


def test_the_run_id_is_the_identity_of_the_input():
    """Documented sharp edge: re-invoking a run replays the journal, even with new input.

    If you want a new input, start a new run. This is what makes replay safe.
    """
    conn = connect()
    run_step(conn, "run-fixed", "plan", lambda: "answer for input A")

    replayed = run_step(conn, "run-fixed", "plan", lambda: "answer for input B")

    assert replayed == "answer for input A"


def test_a_step_name_with_a_newline_still_works():
    """Step names are not validated beyond emptiness: SQLite stores them fine."""
    conn = connect()
    assert run_step(conn, "run-x", "node\n1", lambda: 1) == 1
    assert run_step(conn, "run-x", "node\n1", lambda: 2) == 1


def test_a_gated_step_that_is_approved_does_not_ask_twice():
    conn = connect()
    journal = Journal(conn, "run-gate")
    calls = []

    def gated():
        calls.append(1)
        return "ok"

    node = journal.durable("refund", lambda state: gated(), needs_approval=True, reason="r")

    with pytest.raises(ApprovalRequired):
        node({})

    from duragent import pending_approvals

    approval = pending_approvals(conn, "run-gate")[0]
    approve(conn, approval.id, by="manager")

    assert node({}) == "ok"
    assert len(calls) == 1


def test_two_processes_share_one_journal(tmp_path):
    """A step executed by one process is replayed by another."""
    db = tmp_path / "shared.db"
    script = """
import sys
sys.path.insert(0, sys.argv[2])
from duragent import connect, run_step
conn = connect(sys.argv[1])
print(run_step(conn, "run-shared", "plan", lambda: "computed by the child"))
"""
    child = subprocess.run(
        [sys.executable, "-c", script, str(db), SRC],
        capture_output=True, text=True, timeout=60,
    )
    assert child.returncode == 0, child.stderr
    assert "computed by the child" in child.stdout

    conn = connect(str(db))
    replayed = run_step(conn, "run-shared", "plan", lambda: "computed again in the parent")

    assert replayed == "computed by the child"
    assert find_step(conn, "run-shared", "plan").attempt == 1


def test_a_nan_cost_is_rejected():
    from duragent import charge, set_budget

    conn = connect()
    set_budget(conn, "run-nan", max_cost=1.0)

    with pytest.raises(ValueError):
        charge(conn, "run-nan", cost=float("nan"))


def test_a_worker_can_be_closed_before_it_ever_polls():
    from duragent import Worker

    conn = connect()
    worker = Worker(conn, "never-used", {})
    worker.close()   # must not hang or raise

