"""The HTTP surface, exercised end to end through TestClient."""

from __future__ import annotations

from typing import TypedDict

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("langgraph")

from fastapi.testclient import TestClient  # noqa: E402
from langgraph.graph import END, START, StateGraph  # noqa: E402

from duragent import ApprovalRejected, Journal, connect  # noqa: E402
from duragent.api import create_app  # noqa: E402


class SummaryState(TypedDict, total=False):
    topic: str
    plan: str
    summary: str


class RefundState(TypedDict, total=False):
    amount: int
    refunded: bool
    notified: bool


def handlers(conn):
    def summary(run_id, payload):
        journal = Journal(conn, run_id)
        builder = StateGraph(SummaryState)
        builder.add_node("plan", journal.durable("plan", lambda s: {"plan": "ok"}))
        builder.add_node("write", journal.durable("write", lambda s: {"summary": "done"}))
        builder.add_edge(START, "plan")
        builder.add_edge("plan", "write")
        builder.add_edge("write", END)
        builder.compile().invoke(payload)

    def refund(run_id, payload):
        journal = Journal(conn, run_id)
        builder = StateGraph(RefundState)
        builder.add_node("refund", journal.durable(
            "refund", lambda s: {"refunded": True}, needs_approval=True, reason="refund 500"))
        builder.add_node("notify", journal.durable("notify", lambda s: {"notified": True}))
        builder.add_edge(START, "refund")
        builder.add_edge("refund", "notify")
        builder.add_edge("notify", END)
        builder.compile().invoke(payload)

    return {"summary": summary, "refund": refund}


def make_client():
    conn = connect()
    app = create_app(conn, handlers(conn))
    return TestClient(app), app


def test_a_summary_run_goes_from_pending_to_completed():
    client, app = make_client()

    created = client.post("/api/runs", json={"workflow": "summary", "input": {"topic": "x"}})
    assert created.status_code == 202
    run_id = created.json()["id"]

    assert client.get(f"/api/runs/{run_id}").json()["status"] == "PENDING"

    app.state.worker.poll_once()

    body = client.get(f"/api/runs/{run_id}").json()
    assert body["status"] == "COMPLETED"
    assert [step["step"] for step in body["steps"]] == ["plan", "write"]


def test_a_refund_waits_for_approval_then_finishes():
    client, app = make_client()

    run_id = client.post("/api/runs", json={"workflow": "refund", "input": {"amount": 500}}) \
        .json()["id"]

    app.state.worker.poll_once()
    body = client.get(f"/api/runs/{run_id}").json()
    assert body["status"] == "SUSPENDED"
    assert body["steps"] == [], "the gated step must leave no journal row"

    pending = client.get(f"/api/runs/{run_id}/approvals").json()
    assert len(pending) == 1
    assert pending[0]["node"] == "refund"

    approved = client.post(f"/api/approvals/{pending[0]['id']}/approve",
                           json={"by": "manager@corp"})
    assert approved.status_code == 200
    assert approved.json()["status"] == "APPROVED"

    app.state.worker.poll_once()

    body = client.get(f"/api/runs/{run_id}").json()
    assert body["status"] == "COMPLETED"
    assert [step["step"] for step in body["steps"]] == ["refund", "notify"]


def test_a_rejected_refund_stops_the_run():
    client, app = make_client()

    run_id = client.post("/api/runs", json={"workflow": "refund", "input": {"amount": 900}}) \
        .json()["id"]
    app.state.worker.poll_once()

    approval_id = client.get(f"/api/runs/{run_id}/approvals").json()[0]["id"]
    rejected = client.post(f"/api/approvals/{approval_id}/reject",
                           json={"by": "manager@corp", "comment": "policy"})
    assert rejected.json()["status"] == "REJECTED"

    # The worker picks the un-parked run up, the gate refuses, the run is FAILED.
    with pytest.raises(ApprovalRejected):
        app.state.worker.poll_once()

    assert client.get(f"/api/runs/{run_id}").json()["status"] == "FAILED"


def test_unknown_run_is_a_404_and_unknown_workflow_is_a_400():
    client, _ = make_client()

    assert client.get("/api/runs/does-not-exist").status_code == 404
    assert client.post("/api/runs", json={"workflow": "nope"}).status_code == 400


def test_health():
    client, _ = make_client()
    assert client.get("/api/health").json() == {"status": "ok"}


def test_the_console_page_is_served():
    client, _ = make_client()

    page = client.get("/")

    assert page.status_code == 200
    assert "Duragent" in page.text
    assert "/api/runs" in page.text


def test_runs_can_be_listed_for_the_console():
    client, _ = make_client()

    created = client.post("/api/runs", json={"workflow": "summary", "input": {"topic": "x"}})
    runs = client.get("/api/runs").json()

    assert runs[0]["id"] == created.json()["id"]
    assert runs[0]["workflow"] == "summary"
    assert runs[0]["status"] == "PENDING"


def test_a_run_can_be_forked_through_the_api():
    client, app = make_client()
    run_id = client.post("/api/runs", json={"workflow": "summary", "input": {"topic": "x"}}) \
        .json()["id"]
    app.state.worker.poll_once()

    forked = client.post(f"/api/runs/{run_id}/fork", json={})

    assert forked.status_code == 202
    new_id = forked.json()["id"]
    assert new_id != run_id
    assert forked.json()["forked_from"] == run_id

    app.state.worker.poll_once()
    detail = client.get(f"/api/runs/{new_id}").json()
    assert detail["status"] == "COMPLETED"
    assert [step["step"] for step in detail["steps"]] == ["plan", "write"]


def test_forking_at_an_unknown_step_is_a_400():
    client, app = make_client()
    run_id = client.post("/api/runs", json={"workflow": "summary", "input": {"topic": "x"}}) \
        .json()["id"]
    app.state.worker.poll_once()

    response = client.post(f"/api/runs/{run_id}/fork", json={"at_step": "nope"})

    assert response.status_code == 400


def test_two_runs_can_be_diffed_through_the_api():
    client, app = make_client()
    run_id = client.post("/api/runs", json={"workflow": "summary", "input": {"topic": "x"}}) \
        .json()["id"]
    app.state.worker.poll_once()

    forked = client.post(f"/api/runs/{run_id}/fork", json={"at_step": "write"}).json()["id"]
    app.state.worker.poll_once()

    detail = client.get(f"/api/runs/{forked}").json()
    assert detail["forked_from"] == run_id

    body = client.get(f"/api/runs/{run_id}/diff/{forked}").json()
    assert body["a"] == run_id
    assert body["b"] == forked
    assert [step["step"] for step in body["steps"]] == ["plan", "write"]
    assert "budget" in body
    assert client.get(f"/api/runs/{run_id}/diff/nope").status_code == 404


def test_a_run_can_be_created_with_a_budget():
    client, app = make_client()

    created = client.post("/api/runs", json={
        "workflow": "summary",
        "input": {"topic": "x"},
        "max_cost": 0.5,
        "max_steps": 10,
    })
    run_id = created.json()["id"]
    app.state.worker.poll_once()

    budget = client.get(f"/api/runs/{run_id}").json()["budget"]
    assert budget["max_cost"] == 0.5
    assert budget["max_steps"] == 10
    assert budget["steps"] == 2, "two nodes executed, so two steps were charged"
