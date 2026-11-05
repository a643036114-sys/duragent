"""Durable execution for LangGraph agents.

The idea in one sentence: every step's result is written to a database before
the next step starts, so a crash replays results instead of repeating work.

    from duragent import Journal, connect

    conn = connect("agent.db")
    journal = Journal(conn, run_id)

    answer = journal.durable("summarize", lambda state: llm.invoke(state["prompt"]))(state)

Or, inside a LangGraph graph:

    graph.add_node("summarize", journal.durable("summarize", summarize_fn))
"""

from duragent.db import connect, init_schema
from duragent.graph import Journal, new_run_id
from duragent.steps import (
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
]
