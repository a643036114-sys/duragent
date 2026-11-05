"""Forking: keep the past, replay it for free, and go a different way."""

from __future__ import annotations

from typing import TypedDict

import pytest

pytest.importorskip("langgraph")

from langgraph.graph import END, START, StateGraph  # noqa: E402

from duragent import (  # noqa: E402
    Journal,
    budget_for,
    charge,
    connect,
    enqueue,
    fork_run,
    get_run,
    list_steps,
    set_budget,
    usage,
)


class State(TypedDict, total=False):
    topic: str
    outline: str
    summary: str
    published: bool


def build(conn, run_id: str, calls: dict[str, int]):
    journal = Journal(conn, run_id)

    def plan(state):
        calls["plan"] += 1
        return {"outline": "outline for " + state["topic"]}

    def write(state):
        calls["write"] += 1
        return {"summary": "summary of " + state["outline"]}

    def publish(state):
        calls["publish"] += 1
        return {"published": True}

    builder = StateGraph(State)
    builder.add_node("plan", journal.durable("plan", plan))
    builder.add_node("write", journal.durable("write", write))
    builder.add_node("publish", journal.durable("publish", publish))
    builder.add_edge(START, "plan")
    builder.add_edge("plan", "write")
    builder.add_edge("write", "publish")
    builder.add_edge("publish", END)
    return builder.compile()


@pytest.fixture
def source():
    conn = connect()
    run_id = enqueue(conn, "summary", {"topic": "t"})
    calls = {"plan": 0, "write": 0, "publish": 0}
    build(conn, run_id, calls).invoke({"topic": "t"})
    return conn, run_id, calls


def test_a_clone_replays_everything(source):
    conn, run_id, calls = source
    assert calls == {"plan": 1, "write": 1, "publish": 1}

    forked = fork_run(conn, run_id)

    assert get_run(conn, forked).status == "PENDING"
    assert [record.step_key for record in list_steps(conn, forked)] == ["plan", "write", "publish"]

    build(conn, forked, calls).invoke({"topic": "t"})

    assert calls == {"plan": 1, "write": 1, "publish": 1}, "a clone must execute nothing"


def test_forking_at_a_step_re_executes_it_and_everything_after(source):
    conn, run_id, calls = source

    forked = fork_run(conn, run_id, at_step="write")

    assert [record.step_key for record in list_steps(conn, forked)] == ["plan"], \
        "only the steps before the fork point are kept"

    build(conn, forked, calls).invoke({"topic": "t"})

    assert calls == {"plan": 1, "write": 2, "publish": 2}, \
        "plan replays; write and publish run again"


def test_the_input_can_be_replaced_at_the_fork_point(source):
    conn, run_id, _ = source

    forked = fork_run(conn, run_id, at_step="plan", input={"topic": "another"})

    assert get_run(conn, forked).input == {"topic": "another"}
    assert list_steps(conn, forked) == [], "plan is the first step, so nothing precedes it"


def test_an_unknown_step_is_rejected(source):
    conn, run_id, _ = source

    with pytest.raises(ValueError):
        fork_run(conn, run_id, at_step="never-ran")


def test_an_unknown_run_is_rejected():
    conn = connect()

    with pytest.raises(KeyError):
        fork_run(conn, "no-such-run")


def test_the_budget_limits_are_copied_and_the_spend_is_not(source):
    conn, run_id, _ = source
    set_budget(conn, run_id, max_cost=0.50, max_steps=10)
    charge(conn, run_id, cost=0.10)

    forked = fork_run(conn, run_id)

    limits = budget_for(conn, forked)
    assert limits.max_cost_micros == 500_000
    assert limits.max_steps == 10
    assert usage(conn, forked).cost == 0, "a fork is a new attempt, not a refund"
