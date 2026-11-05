"""Durable execution for LangGraph agents.

The idea in one sentence: every step's result is written to a database before
the next step starts, so a crash replays results instead of repeating work.

    from duragent import Journal, connect, new_run_id

    conn = connect("agent.db")
    journal = Journal(conn, new_run_id())

    graph.add_node("plan", journal.durable("plan", plan_fn))
    graph.add_node("refund", journal.durable(
        "refund", refund_fn, needs_approval=True, reason="refund 500 CNY"))
"""

from duragent.approvals import (
    Approval,
    ApprovalRejected,
    ApprovalRequired,
    approve,
    latest_approval,
    pending_approvals,
    reject,
    request_approval,
)
from duragent.db import connect, init_schema
from duragent.graph import Journal, new_run_id
from duragent.steps import (
    Abort,
    StepInProgress,
    StepRecord,
    find_step,
    run_step,
)

__all__ = [
    "connect",
    "init_schema",
    "find_step",
    "run_step",
    "Journal",
    "new_run_id",
    "StepRecord",
    "StepInProgress",
    "Abort",
    "Approval",
    "ApprovalRequired",
    "ApprovalRejected",
    "approve",
    "reject",
    "latest_approval",
    "pending_approvals",
    "request_approval",
]
