"""The decorator, tested against a real LangGraph graph."""

from __future__ import annotations

from typing import TypedDict

import pytest

pytest.importorskip("langgraph")

from langgraph.graph import END, START, StateGraph  # noqa: E402

from duragent import Journal, connect, new_run_id  # noqa: E402


class State(TypedDict, total=False):
    topic: str
    outline: list[str]
    summary: str
    published: bool


def build(conn, run_id, calls, crash):
    journal = Journal(conn, run_id)

    def plan(state):
        calls["plan"] += 1
        return {"outline": ["intro", "body"]}

    def write(state):
        calls["write"] += 1
        if crash["armed"]:
            crash["armed"] = False
            raise RuntimeError("529 overloaded")
        return {"summary": "done"}

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


def test_a_crashed_node_retries_and_the_others_replay():
    conn = connect()
    run_id = new_run_id()
    calls = {"plan": 0, "write": 0, "publish": 0}
    crash = {"armed": True}
    graph = build(conn, run_id, calls, crash)

    with pytest.raises(RuntimeError):
        graph.invoke({"topic": "x"})

    assert calls == {"plan": 1, "write": 1, "publish": 0}

    final = graph.invoke({"topic": "x"})

    assert final["summary"] == "done"
    assert final["published"] is True
    assert calls["plan"] == 1, "plan must replay, not re-execute"
    assert calls["write"] == 2, "the crashed node must retry"
    assert calls["publish"] == 1


def test_invoking_a_finished_run_changes_nothing():
    conn = connect()
    run_id = new_run_id()
    calls = {"plan": 0, "write": 0, "publish": 0}
    graph = build(conn, run_id, calls, {"armed": False})

    graph.invoke({"topic": "x"})
    graph.invoke({"topic": "x"})
    graph.invoke({"topic": "x"})

    assert calls == {"plan": 1, "write": 1, "publish": 1}


def test_two_runs_do_not_share_a_journal():
    conn = connect()
    calls = {"plan": 0, "write": 0, "publish": 0}

    build(conn, new_run_id(), calls, {"armed": False}).invoke({"topic": "a"})
    build(conn, new_run_id(), calls, {"armed": False}).invoke({"topic": "b"})

    assert calls == {"plan": 2, "write": 2, "publish": 2}, "different run ids = different steps"
