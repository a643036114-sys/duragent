"""Budget limits, and what a graph does when one is hit."""

from __future__ import annotations

from typing import TypedDict

import pytest

pytest.importorskip("langgraph")

from langgraph.graph import END, START, StateGraph  # noqa: E402

from duragent import (  # noqa: E402
    BudgetExceeded,
    Journal,
    charge,
    connect,
    find_step,
    new_run_id,
    set_budget,
    usage,
)


class State(TypedDict, total=False):
    done: bool


def test_charges_accumulate():
    conn = connect()
    set_budget(conn, "run-1", max_cost=1.0)

    charge(conn, "run-1", tokens=100, cost=0.25)
    charge(conn, "run-1", tokens=200, cost=0.25)

    spent = usage(conn, "run-1")
    assert spent.tokens == 300
    assert spent.cost == pytest.approx(0.50)
    assert spent.steps == 0


def test_the_charge_that_does_not_fit_is_refused_and_not_billed():
    conn = connect()
    set_budget(conn, "run-2", max_cost=0.10)

    for _ in range(5):
        charge(conn, "run-2", cost=0.02)

    with pytest.raises(BudgetExceeded) as caught:
        charge(conn, "run-2", cost=0.02)

    assert caught.value.limit == "cost"
    assert usage(conn, "run-2").cost == pytest.approx(0.10), "a refused charge costs nothing"


def test_no_budget_means_unlimited():
    conn = connect()
    assert charge(conn, "run-3", tokens=10**9, cost=10**6, steps=10**6) is None
    assert usage(conn, "run-3") is None


def test_step_limit():
    conn = connect()
    set_budget(conn, "run-4", max_steps=2)

    charge(conn, "run-4", steps=1)
    charge(conn, "run-4", steps=1)
    with pytest.raises(BudgetExceeded) as caught:
        charge(conn, "run-4", steps=1)

    assert caught.value.limit == "steps"


def test_raising_the_budget_keeps_what_was_already_spent():
    conn = connect()
    set_budget(conn, "run-5", max_cost=0.10)
    charge(conn, "run-5", cost=0.10)

    with pytest.raises(BudgetExceeded):
        charge(conn, "run-5", cost=0.01)

    set_budget(conn, "run-5", max_cost=0.20)
    charge(conn, "run-5", cost=0.05)

    assert usage(conn, "run-5").cost == pytest.approx(0.15)


def test_a_burning_node_stops_and_resumes_after_the_budget_is_raised():
    conn = connect()
    run_id = new_run_id()
    journal = Journal(conn, run_id)
    set_budget(conn, run_id, max_cost=0.08)
    calls = {"n": 0}

    def burn(state):
        calls["n"] += 1
        for _ in range(5):
            journal.charge(cost=0.02)
        return {"done": True}

    builder = StateGraph(State)
    builder.add_node("burn", journal.durable("burn", burn))
    builder.add_edge(START, "burn")
    builder.add_edge("burn", END)
    graph = builder.compile()

    with pytest.raises(BudgetExceeded):
        graph.invoke({})

    assert usage(conn, run_id).cost == pytest.approx(0.08), "stopped exactly at the cap"
    assert find_step(conn, run_id, "burn") is None, "an aborted step leaves no journal row"

    set_budget(conn, run_id, max_cost=0.30)
    graph.invoke({})

    assert usage(conn, run_id).cost == pytest.approx(0.18), "0.08 kept, 0.10 new"
    assert calls["n"] == 2


def test_a_replayed_node_does_not_spend_again():
    conn = connect()
    run_id = new_run_id()
    journal = Journal(conn, run_id)
    set_budget(conn, run_id, max_cost=1.0)

    def cheap(state):
        journal.charge(cost=0.10)
        return {"done": True}

    builder = StateGraph(State)
    builder.add_node("cheap", journal.durable("cheap", cheap))
    builder.add_edge(START, "cheap")
    builder.add_edge("cheap", END)
    graph = builder.compile()

    graph.invoke({})
    graph.invoke({})
    graph.invoke({})

    assert usage(conn, run_id).cost == pytest.approx(0.10)
