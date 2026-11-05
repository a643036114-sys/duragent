"""A runnable HTTP demo.

    pip install -e ".[api]"
    python examples/api_app.py

    curl -X POST localhost:8000/api/runs -H 'content-type: application/json' \
         -d '{"workflow":"refund","input":{"amount":500}}'
"""

from __future__ import annotations

import os
import time
from typing import TypedDict

import uvicorn
from langgraph.graph import END, START, StateGraph

from duragent import Journal, connect
from duragent.api import create_app


class SummaryState(TypedDict, total=False):
    topic: str
    plan: str
    summary: str


class RefundState(TypedDict, total=False):
    amount: int
    plan: str
    refunded: bool
    notified: bool


def summary_handler(conn):
    """plan -> write, no human needed."""

    def handle(run_id: str, payload: dict) -> None:
        journal = Journal(conn, run_id)

        def plan(state):
            time.sleep(0.3)
            return {"plan": "write about " + state["topic"]}

        def write(state):
            time.sleep(0.3)
            journal.charge(tokens=1200, cost=0.004)
            return {"summary": "summary of: " + state["plan"]}

        builder = StateGraph(SummaryState)
        builder.add_node("plan", journal.durable("plan", plan))
        builder.add_node("write", journal.durable("write", write))
        builder.add_edge(START, "plan")
        builder.add_edge("plan", "write")
        builder.add_edge("write", END)
        builder.compile().invoke(payload)

    return handle


def refund_handler(conn):
    """plan -> refund (needs a human) -> notify."""

    def handle(run_id: str, payload: dict) -> None:
        journal = Journal(conn, run_id)

        def plan(state):
            return {"plan": f"refund {state.get('amount', 0)} CNY"}

        def refund(state):
            time.sleep(0.4)
            return {"refunded": True}

        def notify(state):
            return {"notified": True}

        builder = StateGraph(RefundState)
        builder.add_node("plan", journal.durable("plan", plan))
        builder.add_node("refund", journal.durable(
            "refund", refund, needs_approval=True,
            reason=lambda state: f"refund {state.get('amount', 0)} CNY to the customer"))
        builder.add_node("notify", journal.durable("notify", notify))
        builder.add_edge(START, "plan")
        builder.add_edge("plan", "refund")
        builder.add_edge("refund", "notify")
        builder.add_edge("notify", END)
        builder.compile().invoke(payload)

    return handle


conn = connect(os.environ.get("DURAGENT_DB", "demo.db"))
app = create_app(
    conn,
    {"summary": summary_handler(conn), "refund": refund_handler(conn)},
    autostart=True,
)


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8000)
