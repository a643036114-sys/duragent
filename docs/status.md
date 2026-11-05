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

```bash
cd python && python -m pytest -q                                   # 49 passed, 6 skipped
DURAGENT_PG_URL=postgresql://... python -m pytest -q               # 55 passed
```

Demos: `examples/crash_demo.py`, `approval_demo.py`, `budget_demo.py`, `worker_demo.py`,
`api_app.py`.

## What is deliberately missing

Listed in [python/README.md](../python/README.md#known-limitations): at-least-once semantics,
step-name identity, no retry policy, approvals never expire, SQLite only, no auth on the API.

## Next, in the order I would do it

1. **Approval expiry.** A run parked forever is a leak; add `expires_at` and a sweep.
2. **Precise retry scheduling.** Backoff is computed inside the step, but a run only returns when
   the worker's lease expires. A `not_before` column on `run` would fix the floor.
3. **Schema migrations.** There is none; an old table is refused, not upgraded.
4. **Publish.** README with a GIF, launch posts. Not before 1–3.

Also open: the JVM tree in `java/` has the same "non-serialisable result leaves the claim
RUNNING" bug that was fixed in Python. Worth fixing when the Java tree gets touched again.

## Decisions worth remembering

- [ADR 0001](adr/0001-durable-step-as-the-primitive.md): journal the step, not the graph.
- [ADR 0002](adr/0002-claim-by-primary-key.md): the primary key is the lock; no `SKIP LOCKED`.
- [ADR 0003](adr/0003-replay-instead-of-snapshot.md): resume by replaying, not by restoring.
- [ADR 0004](adr/0004-aborts-do-not-poison-the-journal.md): an abort releases the claim, it does
  not write `FAILED`.
