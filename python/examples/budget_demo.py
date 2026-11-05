"""An agent that burns money until the budget stops it, then resumes after a raise.

Run it:  python examples/budget_demo.py
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from duragent import (
    BudgetExceeded,
    Journal,
    budget_for,
    connect,
    new_run_id,
    set_budget,
    usage,
)


class State(TypedDict, total=False):
    rounds: int


def main() -> None:
    workdir = Path(tempfile.mkdtemp(prefix="duragent-budget-"))
    conn = connect(str(workdir / "agent.db"))
    run_id = new_run_id()
    journal = Journal(conn, run_id)
    set_budget(conn, run_id, max_cost=0.08)

    rounds = {"n": 0}

    def research(state: State) -> dict:
        rounds["n"] += 1
        for i in range(1, 6):
            print(f"  [research] model call {i}: 0.02 spent")
            journal.charge(cost=0.02, tokens=800)
        return {"rounds": state.get("rounds", 0) + 1}

    builder = StateGraph(State)
    builder.add_node("research", journal.durable("research", research))
    builder.add_edge(START, "research")
    builder.add_edge("research", END)
    graph = builder.compile()

    print(f"run id: {run_id}")
    cap = budget_for(conn, run_id).max_cost_micros / 1_000_000
    print(f"budget: {cap:.2f} allowed\n")

    print("-- attempt 1 --")
    try:
        graph.invoke({})
    except BudgetExceeded as exc:
        print(f"  stopped: {exc.limit} budget ({exc.attempted} > {exc.allowed} micro-units)")

    print(f"  spent so far: {usage(conn, run_id).cost:.2f}")

    print("\n-- a human raises the cap to 0.30 and resumes --")
    set_budget(conn, run_id, max_cost=0.30)
    final = graph.invoke({})

    print(f"\nfinal state: {final}")
    print(f"total spent:  {usage(conn, run_id).cost:.2f}")
    print(f"node executions: {rounds['n']}")
    print("The 0.08 from the first attempt was kept, not thrown away.")


if __name__ == "__main__":
    main()
