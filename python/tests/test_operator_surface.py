"""The operator surface: CLI, metrics, and handing leases back on shutdown."""

from __future__ import annotations

import threading

import pytest

from duragent import Worker, claim, connect, enqueue, get_run, list_runs
from duragent.cli import main as cli_main

pytest.importorskip("fastapi")

from fastapi.testclient import TestClient  # noqa: E402

from duragent.api import create_app  # noqa: E402


def test_list_runs_is_newest_first():
    conn = connect()
    first = enqueue(conn, "a", clock=lambda: 100.0)
    second = enqueue(conn, "b", clock=lambda: 200.0)

    assert [run.id for run in list_runs(conn, 10)] == [second, first]


def test_the_cli_lists_and_inspects_a_run(tmp_path, capsys):
    db = str(tmp_path / "ops.db")
    conn = connect(db)
    run_id = enqueue(conn, "summary", {"topic": "x"})
    conn.close()

    assert cli_main(["--db", db, "runs"]) == 0
    listing = capsys.readouterr().out
    assert run_id[:8] in listing
    assert "PENDING" in listing

    assert cli_main(["--db", db, "inspect", run_id]) == 0
    detail = capsys.readouterr().out
    assert "summary" in detail
    assert "PENDING" in detail


def test_the_cli_reports_an_unknown_run(tmp_path, capsys):
    db = str(tmp_path / "ops.db")

    assert cli_main(["--db", db, "inspect", "nope"]) == 1
    assert "unknown run" in capsys.readouterr().err


def test_the_cli_forks_a_run(tmp_path, capsys):
    from duragent import run_step

    db = str(tmp_path / "ops.db")
    conn = connect(db)
    run_id = enqueue(conn, "summary", {"topic": "x"})
    run_step(conn, run_id, "plan", lambda: {"outline": "o"})
    conn.close()

    assert cli_main(["--db", db, "fork", run_id, "--at", "plan"]) == 0
    forked = capsys.readouterr().out.strip()

    conn = connect(db)
    assert get_run(conn, forked).workflow == "summary"
    conn.close()

    assert cli_main(["--db", db, "fork", run_id, "--at", "nope"]) == 1
    assert "never succeeded" in capsys.readouterr().err


def test_metrics_report_runs_steps_and_spend():
    conn = connect()
    app = create_app(conn, {"noop": lambda run_id, payload: None})
    client = TestClient(app)

    client.post("/api/runs", json={"workflow": "noop", "input": {}})
    app.state.worker.poll_once()

    text = client.get("/metrics").text

    assert 'duragent_runs{status="COMPLETED"} 1' in text
    assert "duragent_steps" in text
    assert "duragent_spend_micros" in text


def test_closing_a_worker_hands_back_its_lease():
    conn = connect()
    run_id = enqueue(conn, "slow")
    started = threading.Event()
    unblock = threading.Event()

    def handler(rid, payload):
        started.set()
        unblock.wait(5)

    worker = Worker(conn, "worker-a", {"slow": handler},
                    lease=60, heartbeat_every=5, sweep_every=None)
    polling = threading.Thread(target=worker.poll_once)
    polling.start()
    try:
        assert started.wait(5), "the handler should have started"
        worker.close()
        # Closing must release the lease, otherwise a redeploy leaves the run
        # locked for the whole lease duration.
        assert claim(conn, "worker-b", lease=60) == run_id
    finally:
        unblock.set()
        polling.join(5)
