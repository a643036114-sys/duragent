# ADR 0004 — Aborts must not poison the step journal

*Status: accepted · 2026-11-05*

## Context

A step can stop for two very different reasons:

1. **It failed.** The model returned 529, the tool threw, the JSON was malformed. Retrying later
   is correct.
2. **It was aborted.** The run ran out of money, out of wall-clock, or a human pressed stop. The
   step did nothing wrong and produced nothing; retrying it immediately would hit the same wall.

The engine's default is to write `FAILED` and let a retry pick it up. Applied to case 2 that is
wrong in a way that only shows up later: the step looks retryable, so a future resume silently
skips the work that never happened and the run "completes" without it.

## Decision

Introduce `AbortSignal`, a marker interface. When a body throws an exception that implements it,
the engine **releases the claim** (`DELETE ... WHERE status = 'RUNNING'`) instead of writing
`FAILED`, then rethrows the original exception unwrapped.

`BudgetExceededException` implements it. Approval pauses are handled one level up, before the
claim is taken, for the same reason.

## Why deletion rather than a new status

- A row that says "this never ran" is indistinguishable from no row, and every read path already
  handles "no row". A new `ABORTED` status would need handling in the claim protocol, the resume
  logic and every query, for zero information gain.
- Deletion makes the retry semantics automatic: after the human raises the budget and resumes,
  the claim succeeds and the step executes. No special case.

## Consequences

- An abort loses the record that an attempt was made. That is intentional: the *budget ledger*
  keeps the spend, and the run keeps the reason, so the history is not actually lost.
- Abort signals must be cheap to detect: the check walks the cause chain, because a node running
  on another thread arrives wrapped in a completion exception (this was a real bug caught by
  `BudgetFlowTest`, not a hypothetical).
- Every future guardrail (rate limit, content policy, kill switch) should throw an `AbortSignal`
  rather than a plain exception, and get the same semantics for free.
