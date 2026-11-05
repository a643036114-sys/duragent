"""Two workers, a queue, and one worker that dies mid-flight.

Run it:  python examples/worker_demo.py
"""

from __future__ import annotations

import tempfile
import time
from pathlib import Path
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from duragent import Journal, Worker, claim, connect, enqueue, get_run, run_step


class State(TypedDict, total=False):
    topic: str
    plan: str
    summary: str


def build_graph(conn, run_id: str, label: str):
    """每个 run 一张新图，但共用同一个数据库——这就是"多进程"的真相。"""
    journal = Journal(conn, run_id)

    def plan(state: State) -> dict:
        print(f"    {label} plan   (model call)")
        time.sleep(0.1)
        return {"plan": "写一篇关于 " + state["topic"] + " 的摘要"}

    def write(state: State) -> dict:
        print(f"    {label} write  (model call)")
        time.sleep(0.1)
        return {"summary": "durable execution, explained"}

    builder = StateGraph(State)
    builder.add_node("plan", journal.durable("plan", plan))
    builder.add_node("write", journal.durable("write", write))
    builder.add_edge(START, "plan")
    builder.add_edge("plan", "write")
    builder.add_edge("write", END)
    return builder.compile()


def handler_for(conn, label: str):
    def handle(run_id: str, payload: dict) -> None:
        build_graph(conn, run_id, label).invoke(payload)

    return handle


def main() -> None:
    workdir = Path(tempfile.mkdtemp(prefix="duragent-worker-"))
    conn = connect(str(workdir / "agent.db"))

    print("-- queue two runs, let one worker drain them --")
    ids = [enqueue(conn, "summary", {"topic": t}) for t in ("crash safety", "approvals")]
    with Worker(conn, "worker-1", {"summary": handler_for(conn, "[w1]")},
                lease=10, heartbeat_every=2) as w1:
        done = w1.drain()
    print(f"  executed {done} run(s)\n")

    print("-- a third run, and the worker that claims it dies --")
    doomed = enqueue(conn, "summary", {"topic": "takeover"})
    claim(conn, "worker-doomed", lease=0.3)      # 领了任务……
    # ……干完第一步，然后进程没了。没有 release，也没有 heartbeat。
    run_step(conn, doomed, "plan", lambda: {"plan": "第一步的结果"})
    print("  worker-doomed finished 'plan', then vanished (no release, no heartbeat)")
    time.sleep(0.4)

    with Worker(conn, "worker-2", {"summary": handler_for(conn, "[w2]")},
                lease=10, heartbeat_every=2) as w2:
        assert w2.poll_once(), "the expired lease makes the run claimable again"

    print("\n-- final state --")
    for run_id in ids + [doomed]:
        run = get_run(conn, run_id)
        print(f"  {run_id[:8]}  {run.status}")

    print("\nworker-2 took the dead worker's run over and replayed what was already done.")


if __name__ == "__main__":
    main()
