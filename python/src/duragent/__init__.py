"""Durable execution for LangGraph agents.

The idea in one sentence: every step's result is written to a database before
the next step starts, so a crash replays results instead of repeating work.

    from duragent import connect, run_step

    conn = connect("agent.db")
    answer = run_step(conn, run_id, "summarize", lambda: llm(prompt))

Call it again - in a new process, after a restart - and the lambda does not
run. The stored result is returned instead.
"""

from duragent.db import connect, init_schema
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
    "StepRecord",
    "StepInProgress",
]
