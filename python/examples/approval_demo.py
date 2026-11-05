"""A refund that waits for a human, then finishes without repeating earlier work.

Run it:  python examples/approval_demo.py
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from duragent import Journal, approve, connect, find_step, new_run_id, pending_approvals


class State(TypedDict, total=False):
    ticket: str
    plan: str
    refunded: bool
    notified: bool


def main() -> None:
    workdir = Path(tempfile.mkdtemp(prefix="duragent-approval-"))
    conn = connect(str(workdir / "agent.db"))
    run_id = new_run_id()
    journal = Journal(conn, run_id)
    calls = {"plan": 0, "refund": 0, "notify": 0}

    def plan(state: State) -> dict:
        calls["plan"] += 1
        print("  [plan]   deciding what to do ...")
        return {"plan": "refund 500 CNY"}

    def refund(state: State) -> dict:
        calls["refund"] += 1
        print("  [refund] moving money ...")
        return {"refunded": True}

    def notify(state: State) -> dict:
        calls["notify"] += 1
        print("  [notify] emailing the customer ...")
        return {"notified": True}

    builder = StateGraph(State)
    builder.add_node("plan", journal.durable("plan", plan))
    builder.add_node("refund", journal.durable(
        "refund", refund, needs_approval=True, reason="refund 500 CNY to the customer"))
    builder.add_node("notify", journal.durable("notify", notify))
    builder.add_edge(START, "plan")
    builder.add_edge("plan", "refund")
    builder.add_edge("refund", "notify")
    builder.add_edge("notify", END)
    graph = builder.compile()

    print(f"run id: {run_id}")
    print("\n-- the agent runs until the money-moving node --")
    try:
        graph.invoke({"ticket": "T-9"})
    except Exception as exc:
        print(f"  paused: {type(exc).__name__}")

    print(f"  refund step journaled? {find_step(conn, run_id, 'refund')}  <- must be None")

    waiting = pending_approvals(conn, run_id)
    print(f"  waiting for a human: {len(waiting)} request(s)")
    for a in waiting:
        print(f"    {a.node}: {a.reason}")

    print("\n-- the manager says yes, possibly hours later --")
    approve(conn, waiting[0].id, by="manager@corp", comment="ok, VIP customer")

    print("\n-- the agent resumes --")
    final = graph.invoke({"ticket": "T-9"})
    print(f"\nfinal state: {final}")
    print(f"actual executions: {calls}")
    print("plan ran once: the second run replayed it from the journal.")


if __name__ == "__main__":
    main()
