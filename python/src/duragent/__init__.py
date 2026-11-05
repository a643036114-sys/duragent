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
    ApprovalExpired,
    ApprovalRejected,
    ApprovalRequired,
    approve,
    expire_stale,
    latest_approval,
    pending_approvals,
    reject,
    request_approval,
)
from duragent.budgets import (
    Budget,
    BudgetExceeded,
    Usage,
    budget_for,
    charge,
    set_budget,
    usage,
)
from duragent.db import connect, init_schema
from duragent.diff import RunDiff, StepDiff, diff_runs, format_diff
from duragent.fork import fork_run
from duragent.graph import Journal, new_run_id
from duragent.retry import RetriesExhausted, Retry, RetryLater
from duragent.runs import (
    CANCELED,
    COMPLETED,
    FAILED,
    PENDING,
    RUNNING,
    SUSPENDED,
    Run,
    claim,
    enqueue,
    get_run,
    heartbeat,
    list_runs,
    release,
    schedule,
    set_status,
)
from duragent.steps import (
    Abort,
    InputChanged,
    StepInProgress,
    StepRecord,
    find_step,
    list_steps,
    run_step,
)
from duragent.worker import Worker

__all__ = [
    "connect",
    "init_schema",
    "find_step",
    "list_steps",
    "run_step",
    "Journal",
    "new_run_id",
    "fork_run",
    "diff_runs",
    "format_diff",
    "RunDiff",
    "StepDiff",
    "StepRecord",
    "StepInProgress",
    "InputChanged",
    "Abort",
    "Approval",
    "ApprovalRequired",
    "ApprovalRejected",
    "ApprovalExpired",
    "expire_stale",
    "approve",
    "reject",
    "latest_approval",
    "pending_approvals",
    "request_approval",
    "Budget",
    "BudgetExceeded",
    "Usage",
    "budget_for",
    "set_budget",
    "charge",
    "usage",
    "Worker",
    "Run",
    "enqueue",
    "get_run",
    "set_status",
    "claim",
    "heartbeat",
    "release",
    "schedule",
    "list_runs",
    "PENDING",
    "RUNNING",
    "SUSPENDED",
    "COMPLETED",
    "FAILED",
    "CANCELED",
    "Retry",
    "RetryLater",
    "RetriesExhausted",
]


def __getattr__(name: str):
    """Keep the package import-free of web dependencies until someone asks for the API."""
    if name == "create_app":
        from duragent.api import create_app

        return create_app
    raise AttributeError(f"module 'duragent' has no attribute {name!r}")
