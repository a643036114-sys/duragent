"""A node that moves money waits for a human, without holding a process."""

from __future__ import annotations

from typing import TypedDict

import pytest

pytest.importorskip("langgraph")

from langgraph.graph import END, START, StateGraph  # noqa: E402

from duragent import (  # noqa: E402
    ApprovalRejected,
    ApprovalRequired,
    Journal,
    approve,
    connect,
    find_step,
    new_run_id,
    pending_approvals,
    reject,
)


class State(TypedDict, total=False):
    ticket: str
    plan: str
    refunded: bool
    notified: bool


def build(conn, run_id, calls):
    journal = Journal(conn, run_id)

    def plan(state):
        calls["plan"] += 1
        return {"plan": "refund 500 CNY"}

    def refund(state):
        calls["refund"] += 1
        return {"refunded": True}

    def notify(state):
        calls["notify"] += 1
        return {"notified": True}

    builder = StateGraph(State)
    builder.add_node("plan", journal.durable("plan", plan))
    builder.add_node("refund", journal.durable(
        "refund", refund, needs_approval=True, reason="refund 500 CNY"))
    builder.add_node("notify", journal.durable("notify", notify))
    builder.add_edge(START, "plan")
    builder.add_edge("plan", "refund")
    builder.add_edge("refund", "notify")
    builder.add_edge("notify", END)
    return builder.compile()


def test_a_gated_node_parks_before_the_side_effect():
    conn = connect()
    run_id = new_run_id()
    calls = {"plan": 0, "refund": 0, "notify": 0}
    graph = build(conn, run_id, calls)

    with pytest.raises(ApprovalRequired):
        graph.invoke({"ticket": "T-9"})

    assert calls == {"plan": 1, "refund": 0, "notify": 0}, "the refund must not happen yet"
    assert find_step(conn, run_id, "refund") is None, "an aborted step leaves no journal row"
    assert len(pending_approvals(conn, run_id)) == 1


def test_approval_lets_the_run_finish_without_repeating_the_first_node():
    conn = connect()
    run_id = new_run_id()
    calls = {"plan": 0, "refund": 0, "notify": 0}
    graph = build(conn, run_id, calls)

    with pytest.raises(ApprovalRequired):
        graph.invoke({"ticket": "T-9"})

    approval = pending_approvals(conn, run_id)[0]
    approve(conn, approval.id, by="manager@corp")

    final = graph.invoke({"ticket": "T-9"})

    assert final["refunded"] is True and final["notified"] is True
    assert calls["plan"] == 1, "plan must replay from the journal"
    assert calls["refund"] == 1
    assert calls["notify"] == 1


def test_rejection_stops_the_run_for_good():
    conn = connect()
    run_id = new_run_id()
    calls = {"plan": 0, "refund": 0, "notify": 0}
    graph = build(conn, run_id, calls)

    with pytest.raises(ApprovalRequired):
        graph.invoke({"ticket": "T-9"})

    approval = pending_approvals(conn, run_id)[0]
    reject(conn, approval.id, by="manager@corp", comment="policy violation")

    with pytest.raises(ApprovalRejected):
        graph.invoke({"ticket": "T-9"})

    assert calls["refund"] == 0


def test_the_first_decision_wins():
    conn = connect()
    run_id = new_run_id()
    graph = build(conn, run_id, {"plan": 0, "refund": 0, "notify": 0})

    with pytest.raises(ApprovalRequired):
        graph.invoke({"ticket": "T-9"})

    approval = pending_approvals(conn, run_id)[0]
    first = approve(conn, approval.id, by="manager-a")
    second = approve(conn, approval.id, by="manager-b")

    assert first.status == "APPROVED"
    assert second.decided_by == "manager-a", "a second click changes nothing"
