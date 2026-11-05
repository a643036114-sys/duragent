# Status

Where the project stands, so a break of a few days does not cost a day of re-reading.

*Last updated: 2026-11-05, after the PostgreSQL adapter.*

## What works

| Capability | Where | Proof |
|---|---|---|
| A step runs once, later calls replay | `python/src/duragent/steps.py` | `tests/test_steps.py` |
| LangGraph nodes wrapped in one line | `python/src/duragent/graph.py` | `tests/test_langgraph.py` |
| A gated node waits for a human | `python/src/duragent/approvals.py` | `tests/test_approvals.py` |
| Budgets stop a run at the cap, resume after a raise | `python/src/duragent/budgets.py` | `tests/test_budgets.py` |
| Workers claim runs, die, get taken over | `runs.py` + `worker.py` | `tests/test_worker.py` |
| HTTP API: create, inspect, approve | `python/src/duragent/api.py` | `tests/test_api.py` |
| `kill -9` mid-step is recovered | — | `tests/test_hardening.py` |
| SQLite **and** PostgreSQL | `python/src/duragent/db.py` | `tests/test_postgres.py` |
| Retry backoff, approval deadlines, sweep thread | `retry.py`, `runs.py`, `worker.py` | `test_retry.py`, `test_approval_expiry.py`, `test_scheduling.py` |
| Input fingerprints catch stale replays | `graph.py` + `steps.py` | `tests/test_fingerprint.py` |
| CLI (`duragent inspect`) and `/metrics` | `cli.py`, `api.py` | `tests/test_operator_surface.py` |
| A real model call, priced from usage | `examples/real_llm_demo.py` | `tests/test_real_llm_example.py` (fake provider) |
| 200 runs over 4 workers, no duplicate steps | `benchmarks/load_test.py` | `tests/test_load.py` |

```bash
cd python && python -m pytest -q                                   # 80 passed, 6 skipped
DURAGENT_PG_URL=postgresql://... python -m pytest -q               # 86 passed
```

CI (`.github/workflows/ci.yml`) runs the suite on Python 3.11 and 3.13 with a PostgreSQL service.

Demos: `examples/crash_demo.py`, `approval_demo.py`, `budget_demo.py`, `worker_demo.py`,
`api_app.py`.

## What is deliberately missing

Listed in [python/README.md](../python/README.md#known-limitations): at-least-once semantics,
step-name identity, no retry policy, approvals never expire, SQLite only, no auth on the API.

## Next, in the order I would do it

1. **Publish.** README with a GIF, a five-minute quickstart, launch posts. The runtime semantics
   are now complete enough to be shown.
2. **Fairness.** `claim` takes the oldest run first; one noisy workflow can starve the rest.
3. **Real migrations.** Additive columns are handled; anything else is not.
Also open: the JVM tree in `java/` has the same "non-serialisable result leaves the claim
RUNNING" bug that was fixed in Python. Worth fixing when the Java tree gets touched again.

## Decisions worth remembering

- [ADR 0001](adr/0001-durable-step-as-the-primitive.md): journal the step, not the graph.
- [ADR 0002](adr/0002-claim-by-primary-key.md): the primary key is the lock; no `SKIP LOCKED`.
- [ADR 0003](adr/0003-replay-instead-of-snapshot.md): resume by replaying, not by restoring.
- [ADR 0004](adr/0004-aborts-do-not-poison-the-journal.md): an abort releases the claim, it does
  not write `FAILED`.
