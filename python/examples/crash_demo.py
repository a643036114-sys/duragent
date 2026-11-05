"""A three-node LangGraph agent that crashes mid-way and resumes without repeating work.

Run it:  python examples/crash_demo.py
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from duragent import Journal, connect, list_steps, new_run_id


class State(TypedDict, total=False):
    topic: str
    outline: list[str]
    summary: str
    published: bool


def main() -> None:
    workdir = Path(tempfile.mkdtemp(prefix="duragent-demo-"))
    conn = connect(str(workdir / "agent.db"))
    run_id = new_run_id()

    calls = {"plan": 0, "write": 0, "publish": 0}
    crash = {"armed": True}

    journal = Journal(conn, run_id)

    def plan(state: State) -> dict:
        calls["plan"] += 1
        print("  [plan]   calling the model ...")
        return {"outline": ["intro", "body"]}

    def write(state: State) -> dict:
        calls["write"] += 1
        if crash["armed"]:
            crash["armed"] = False
            raise RuntimeError("529 overloaded (simulated upstream failure)")
        print("  [write]  calling the model ...")
        return {"summary": "durable execution, explained"}

    def publish(state: State) -> dict:
        calls["publish"] += 1
        print("  [publish] writing the file ...")
        return {"published": True}

    builder = StateGraph(State)
    builder.add_node("plan", journal.durable("plan", plan))
    builder.add_node("write", journal.durable("write", write))
    builder.add_node("publish", journal.durable("publish", publish))
    builder.add_edge(START, "plan")
    builder.add_edge("plan", "write")
    builder.add_edge("write", "publish")
    builder.add_edge("publish", END)
    graph = builder.compile()

    print(f"run id: {run_id}")
    print("\n-- attempt 1 --")
    try:
        graph.invoke({"topic": "durable execution"})
    except RuntimeError as exc:
        print(f"  crashed at 'write': {exc}")

    print("\n-- attempt 2: a new process, same database --")
    final = graph.invoke({"topic": "durable execution"})

    print("\n-- journal after two attempts --")
    print(f"  {'step':<12} {'status':<10} {'attempt':<8} executions")
    for record in list_steps(conn, run_id):
        print(f"  {record.step_key:<12} {record.status:<10} {record.attempt:<8} {calls[record.step_key]}")

    print(f"\nfinal state: {final.get('summary')!r}, published={final.get('published')}")
    print("plan ran once: the restart replayed it from the journal instead of calling the model again.")


if __name__ == "__main__":
    main()
