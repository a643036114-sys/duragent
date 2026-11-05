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

## Budgets

```python
set_budget(conn, run_id, max_cost=0.50, max_tokens=200_000, max_steps=30)

# inside a node, where the cost is actually known
journal.charge(cost=0.02, tokens=1200)
```

A charge is one conditional `UPDATE`, so two workers cannot spend the last cent twice. When a
charge does not fit, nothing is billed: the run stops with `BudgetExceeded`, the step is released
(not marked failed), and raising the cap resumes it where it left off.

Money is stored as integer micro-units. A float ledger drifts.

## Workers

```python
run_id = enqueue(conn, "summary", {"topic": "x"})

with Worker(conn, "worker-1", {"summary": handler}, lease=30, heartbeat_every=5) as worker:
    worker.drain()
```

A run is claimable when it is `PENDING`, or `RUNNING` with no live lease. The lease is renewed in
the background while the handler works, so a worker that dies is simply taken over once it
expires — and the takeover replays finished nodes instead of repeating them.

The worker turns exceptions into run states, which is the one place worth reading twice:

| Raised by the handler | Run becomes |
|---|---|
| `ApprovalRequired` | `SUSPENDED` — waiting for a human |
| `BudgetExceeded` | `CANCELED` — a guardrail stopped it |
| anything else | `FAILED` |

## HTTP API

```bash
pip install -e ".[api]"
python examples/api_app.py
```

```bash
curl -X POST localhost:8000/api/runs -H 'content-type: application/json' \
     -d '{"workflow":"refund","input":{"amount":500}}'
# {"id":"9c987f79-...","status":"PENDING"}

curl localhost:8000/api/runs/<id>
# {"status":"SUSPENDED","steps":[{"step":"plan",...}],"approvals":[{...}]}

curl -X POST localhost:8000/api/approvals/<aid>/approve \
     -H 'content-type: application/json' -d '{"by":"manager@corp"}'

curl localhost:8000/api/runs/<id>
# {"status":"COMPLETED","steps":[plan, refund, notify]}
```

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

## Known limitations

A runtime that hides its edges is worse than one that names them.

- **At least once, not exactly once.** A crash between "the side effect happened" and "the result
  was written" re-runs the function. Pass the step name downstream as an idempotency key.
- **The step name is the identity.** Renaming a step re-executes it, and a loop over the same node
  needs `charge#1`, `charge#2`, ... until the runner owns that bookkeeping.
- **The run id is the identity of the input.** Invoking a run again with a different payload
  replays the old results.
- **No retry policy.** A `FAILED` step retries on the next call, immediately. Backoff is the
  caller's job.
- **Approvals never expire.** A run can wait forever; an expiry policy is not implemented.
- **SQLite only for now.** The SQL is portable, but there is no Postgres adapter yet.
- **No authentication on the API.** It is a demo, not a deployment.

## Status

`0.1.0`: durable steps, the LangGraph wrapper, approvals, budgets, a worker queue and an HTTP
API. 49 tests, including a real `kill -9` recovery test and a two-process journal test.

A complete JVM implementation of the same design lives in [`../java/`](../java/).
