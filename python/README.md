# duragent (Python)

Durable execution for LangGraph agents.

Every step writes its result to a database before the next step starts, so a crash replays
results instead of repeating work.

```python
from duragent import Journal, connect, new_run_id

conn = connect("agent.db")
journal = Journal(conn, new_run_id())

graph.add_node("plan",   journal.durable("plan", plan_fn))
graph.add_node("refund", journal.durable("refund", refund_fn, needs_approval=True))
```

Call it again — new process, next morning, after a redeploy — and a finished node **does not run**.
The stored result comes back instead.

## Install

```bash
pip install -e ".[dev]"
pytest
```

No required dependency. SQLite comes from the standard library.

## With LangGraph

A LangGraph node is just a function `state -> dict`, so wrapping it is enough:

```python
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

## Approvals

```python
journal.durable("refund", refund_fn, needs_approval=True,
                reason="refund 500 CNY", expires_in=3600)
```

The first call parks the run as `SUSPENDED` and raises `ApprovalRequired`; the process is free to
exit. `approve(conn, approval_id, by="manager")` re-opens the run and the next invoke executes the
node exactly once. `reject` fails the run.

`expires_in` is a deadline. An overdue request is closed either by the next worker that reaches the
gate or by the worker's sweep thread, and the run fails instead of waiting forever.

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

## Retries

```python
journal.durable("call-llm", call_model, retry=Retry(max_attempts=4, base=1.0, multiplier=2.0))
```

Waits are 1s, 2s, 4s, capped by `max_delay`. While a step is backing off it raises `RetryLater`
instead of running, so a model that returns 529 is not hammered. The worker stores a `not_before`
deadline on the run and the claim query skips it until then, so the retry happens on schedule
rather than whenever a lease happens to expire.

## Workers

```python
run_id = enqueue(conn, "summary", {"topic": "x"})

with Worker(conn, "worker-1", {"summary": handler}, lease=30, heartbeat_every=5) as worker:
    worker.drain()
```

A run is claimable when it is `PENDING`, or `RUNNING` with no live lease, and its `not_before` has
passed. The lease is renewed in the background while the handler works; a worker that dies is
simply taken over once it expires, and the takeover replays finished nodes instead of repeating
them.

The worker turns exceptions into run states, which is the one place worth reading twice:

| Raised by the handler | Run becomes |
|---|---|
| `ApprovalRequired` | `SUSPENDED` — waiting for a human |
| `BudgetExceeded` | `CANCELED` — a guardrail stopped it |
| `RetryLater` | still `RUNNING`, scheduled for later |
| anything else | `FAILED` |

Closing a worker releases the leases it holds, so a redeploy does not leave runs locked.

## Fingerprints

```python
journal.durable("plan", plan_fn, fingerprint=True)
```

The journal is keyed by step name, so a replay would happily return a result computed from a
*different* input — the "I changed the prompt and nothing happened" bug. With `fingerprint=True`
the input is hashed and a mismatch raises `InputChanged` instead of silently returning stale work.

## CLI

```bash
duragent runs --limit 10
duragent inspect <run_id>
duragent --db postgresql://... runs
```

Prints the run's status, its steps with attempts and errors, the budget spend and the approvals
still waiting.

## HTTP API

```bash
pip install -e ".[api]"
python examples/api_app.py
```

```bash
curl -X POST localhost:8000/api/runs -H 'content-type: application/json' \
     -d '{"workflow":"refund","input":{"amount":500}}'
curl localhost:8000/api/runs/<id>
curl -X POST localhost:8000/api/approvals/<aid>/approve \
     -H 'content-type: application/json' -d '{"by":"manager@corp"}'
```

`GET /metrics` reports runs by status, steps by status and total spend in Prometheus text format.

## What it guarantees

| Situation | Behaviour |
|---|---|
| Step already succeeded | result replayed, function not called |
| Step failed | recorded as `FAILED`, retried per policy or immediately |
| Process died mid-step | lease expires, another worker re-executes it |
| Two workers, same step | one wins the claim, the other gets `StepInProgress` |
| Result is not JSON | `TypeError` — the journal stores JSON, so it must be |

The honest limit: the function runs **at least once**, its result is stored **at most once**.
A crash between "the side effect happened" and "the result was written" re-runs the function.
`journal.key("refund")` gives you a stable idempotency key to hand downstream and close that window.

## PostgreSQL

```bash
docker compose up -d postgres          # from the repository root
pip install -e ".[postgres]"

DURAGENT_PG_URL=postgresql://duragent:duragent@127.0.0.1:5432/duragent pytest -q
```

```python
conn = connect("postgresql://duragent:duragent@127.0.0.1:5432/duragent")
```

Same SQL, same behaviour: the backend only translates `?` to `%s` and maps unique violations.

## Known limitations

A runtime that hides its edges is worse than one that names them.

- **At least once, not exactly once.** A crash between the side effect and the write re-runs the
  function. Use `journal.key(...)` downstream to close that window.
- **The step name is the identity.** Renaming a step re-executes it, and a loop over the same node
  needs `charge#1`, `charge#2`, ... until the runner owns that bookkeeping.
- **The run id is the identity of the input.** Re-invoking with a different payload replays the old
  results — unless `fingerprint=True`, which then raises `InputChanged`.
- **Approval expiry needs a running worker.** The sweep runs in the worker's thread; with no worker,
  nothing closes overdue approvals.
- **Migrations are additive only.** A missing column is added automatically; a changed type or a
  dropped column is refused, not upgraded.
- **Claiming is first-come by creation time.** One noisy workflow can starve the others.
- **No authentication on the API.** It is a demo, not a deployment.

## Status

`0.1.0`: durable steps, the LangGraph wrapper, approvals with deadlines, budgets, retries, a worker
queue with leases and a sweep thread, fingerprints, a CLI and an HTTP API. 82 tests with
PostgreSQL, 76 on SQLite.

A complete JVM implementation of the same design lives in [`../java/`](../java/).
