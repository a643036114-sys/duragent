"""A flaky model call that backs off, then succeeds.

Run it:  python examples/retry_demo.py
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from duragent import Journal, Retry, RetryLater, connect, new_run_id


class State(TypedDict, total=False):
    answer: str


class FakeClock:
    """A clock we can move, so the demo does not actually sleep for 30 seconds."""

    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def main() -> None:
    workdir = Path(tempfile.mkdtemp(prefix="duragent-retry-"))
    conn = connect(str(workdir / "agent.db"))
    run_id = new_run_id()
    clock = FakeClock()
    journal = Journal(conn, run_id, clock=clock)

    policy = Retry(max_attempts=4, base=1.0, multiplier=2.0, max_delay=10.0)
    attempts = {"n": 0}

    def call_model(state: State) -> dict:
        attempts["n"] += 1
        if attempts["n"] < 4:
            print(f"  attempt {attempts['n']}: 529 overloaded")
            raise RuntimeError("529 overloaded")
        print(f"  attempt {attempts['n']}: success")
        return {"answer": "ok"}

    builder = StateGraph(State)
    builder.add_node("call", journal.durable("call", call_model, retry=policy))
    builder.add_edge(START, "call")
    builder.add_edge("call", END)
    graph = builder.compile()

    print(f"run id: {run_id}\n")
    for _ in range(10):
        try:
            print(f"t={clock.now:5.1f}s  invoke")
            final = graph.invoke({})
            print(f"\nfinal state: {final}")
            print(f"body executions: {attempts['n']}")
            return
        except RetryLater as exc:
            print(f"  backing off {exc.ready_in:.1f}s (t={clock.now:.1f}s)")
            clock.advance(exc.ready_in)
        except RuntimeError as exc:
            print(f"  failed: {exc}")
            clock.advance(0.1)   # the next call asks the policy how long to wait


if __name__ == "__main__":
    main()
