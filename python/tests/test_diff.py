"""Diffing two runs: what actually changed between the branch and its source."""

from __future__ import annotations

from typing import TypedDict

import pytest

pytest.importorskip("langgraph")

from langgraph.graph import END, START, StateGraph  # noqa: E402

from duragent import (  # noqa: E402
    Journal,
    connect,
    diff_runs,
    enqueue,
    fork_run,
    format_diff,
)


class State(TypedDict, total=False):
    topic: str
    plan: str
    summary: str


def build(conn, run_id: str, mark: str):
    journal = Journal(conn, run_id)

    def plan(state):
        return {"plan": f"plan-{mark}"}

    def write(state):
        return {"summary": f"summary-{mark}"}

    builder = StateGraph(State)
    builder.add_node("plan", journal.durable("plan", plan))
    builder.add_node("write", journal.durable("write", write))
    builder.add_edge(START, "plan")
    builder.add_edge("plan", "write")
    builder.add_edge("write", END)
    return builder.compile()


def test_identical_runs_have_no_changes():
    conn = connect()
    first = enqueue(conn, "summary", {"topic": "t"})
    build(conn, first, "v1").invoke({"topic": "t"})
    second = fork_run(conn, first)

    build(conn, second, "v1").invoke({"topic": "t"})
    result = diff_runs(conn, first, second)

    assert [step.summary() for step in result.steps] == ["same", "same"]
    assert result.changed_steps == ()


def test_a_changed_later_step_shows_up_and_the_earlier_one_does_not():
    conn = connect()
    original = enqueue(conn, "summary", {"topic": "t"})
    build(conn, original, "v1").invoke({"topic": "t"})

    forked = fork_run(conn, original, at_step="write")
    build(conn, forked, "v2").invoke({"topic": "t"})

    result = diff_runs(conn, original, forked)
    by_name = {step.step: step for step in result.steps}

    assert by_name["plan"].changed is False, "plan was inherited, not recomputed"
    assert by_name["write"].changed is True

    diff_text = "\n".join(by_name["write"].lines)
    assert '-  "summary": "summary-v1"' in diff_text
    assert '+  "summary": "summary-v2"' in diff_text


def test_a_step_that_only_exists_in_one_run_is_reported():
    conn = connect()
    short = enqueue(conn, "two", {})
    Journal(conn, short).durable("plan", lambda s: {"plan": "x"})({})

    long = enqueue(conn, "two", {})
    journal = Journal(conn, long)
    journal.durable("plan", lambda s: {"plan": "x"})({})
    journal.durable("write", lambda s: {"summary": "y"})({})

    result = diff_runs(conn, short, long)
    by_name = {step.step: step for step in result.steps}

    assert by_name["write"].a_status is None
    assert by_name["write"].summary() == "only in b"
    assert by_name["plan"].changed is False


def test_an_unknown_run_is_rejected():
    conn = connect()
    known = enqueue(conn, "x", {})

    with pytest.raises(KeyError):
        diff_runs(conn, known, "no-such-run")


def test_the_text_rendering_names_both_runs_and_the_changed_step():
    conn = connect()
    original = enqueue(conn, "summary", {"topic": "t"})
    build(conn, original, "v1").invoke({"topic": "t"})
    forked = fork_run(conn, original, at_step="write")
    build(conn, forked, "v2").invoke({"topic": "t"})

    text = format_diff(diff_runs(conn, original, forked))

    assert "a  " + original in text
    assert "b  " + forked in text
    assert "write" in text
    assert "changed" in text
    assert "--- a/write" in text


def test_the_forked_run_records_its_parent():
    conn = connect()
    original = enqueue(conn, "summary", {"topic": "t"})
    build(conn, original, "v1").invoke({"topic": "t"})

    forked = fork_run(conn, original)

    from duragent import get_run

    assert get_run(conn, forked).forked_from == original
