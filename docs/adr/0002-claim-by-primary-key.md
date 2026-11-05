# ADR 0002 — Claim by primary key, not by row locks

*Status: accepted · 2026-11-05*

## Context

Exactly one worker may own a step. The textbook options are `SELECT ... FOR UPDATE`,
`SELECT ... FOR UPDATE SKIP LOCKED`, PostgreSQL advisory locks, or a distributed lock in Redis.

## Decision

Use a plain `INSERT` whose primary key `(run_id, step_key)` arbitrates, plus conditional
`UPDATE ... WHERE status = 'RUNNING'` for every later transition. Row count is the truth.

## Why

- **Portability.** `SKIP LOCKED` is PostgreSQL/MySQL 8 specific and behaves differently on H2.
  Integrity-violation handling is SQLState class `23` on every mainstream driver.
- **No connection held across the side effect.** A model call can take 30 seconds. Holding a row
  lock or a pooled connection for that long is how agent backends die under load.
- **The lock is data.** Because ownership lives in a row, a crashed worker's claim is still
  visible, and a lease timestamp is enough to hand the work to someone else.
- **Testable.** The failure mode (two workers racing) is reproducible in a unit test with two
  threads and an in-memory database, no cluster required.

## Consequences

- Insert conflicts are control flow, not errors. `StepAlreadyClaimedException` is expected and
  is converted into either a replay or `StepInProgressException`.
- Lease length is a tuning knob with a real trade-off: too short and a live-but-slow step gets
  duplicated; too long and a crash stalls the run. Default is 2 minutes, and takeover increments
  `attempt` so duplication is at least visible after the fact.
