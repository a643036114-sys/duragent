# duragent (Python)

Durable execution for LangGraph agents.

Every step writes its result to a database before the next step starts, so a crash replays
results instead of repeating work.

```python
from duragent import connect, run_step

conn = connect("agent.db")

answer = run_step(conn, run_id, "summarize", lambda: llm.invoke(prompt))
```

Call it again — new process, next morning, after a redeploy — and the lambda **does not run**.
The stored result comes back instead.

## Install

```bash
pip install -e ".[dev]"
pytest
```

No runtime dependencies. SQLite is in the standard library; the SQL runs unchanged on Postgres.

## With LangGraph

A LangGraph node is just a function `state -> dict`, so wrapping it is enough:

```python
from duragent import Journal, connect, new_run_id

journal = Journal(connect("agent.db"), new_run_id())

builder.add_node("plan", journal.durable("plan", plan_fn))
builder.add_node("write", journal.durable("write", write_fn))
```

Or as a decorator:

```python
@journal.durable("plan")
def plan(state):
    return {"outline": [...]}
```

See [`examples/crash_demo.py`](examples/crash_demo.py) for a three-node graph that crashes in the
middle and resumes without repeating the first node.

## What it guarantees

| Situation | Behaviour |
|---|---|
| Step already succeeded | result replayed, function not called |
| Step failed | recorded as `FAILED`, next call retries it |
| Process died mid-step | lease expires, another worker re-executes it |
| Two workers, same step | one wins the claim, the other gets `StepInProgress` |
| Result is not JSON | `TypeError` — the journal stores JSON, so it must be |

The honest limit: the function runs **at least once**, its result is stored **at most once**.
A crash between "the side effect happened" and "the result was written" re-runs the function.
Pass the step name to the downstream API as its idempotency key to close that window.

## Status

This is step one: `run_step` plus six tests. Runs, approvals, budgets and the LangGraph
decorator come next — the design is in [`../docs/adr/`](../docs/adr/).

A complete JVM implementation of the same design lives in [`../java/`](../java/).
