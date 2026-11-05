# ADR 0003 — Replay the journal instead of restoring a snapshot

*Status: accepted · 2026-11-05*

## Context

LangGraph4j ships its own checkpointer. The obvious integration is: let it snapshot graph state
after every super-step, resume from the last snapshot, and let Duragent only guard the side
effects. Two implementations were possible.

## Decision

Do not use LangGraph4j's checkpointer in the adapter. On resume, re-run the graph from `START`.
Every node returns its journaled output, so the state rebuilds itself, and only the first
unfinished node touches the outside world.

## Why

- **One source of truth.** A snapshot and a journal can disagree after a crash (snapshot at N,
  journal at N+1). Deciding which one is right is the kind of bug that shows up as "the customer
  was charged but the ticket says it was not". With replay there is one truth: the journal.
- **Cheap in the only regime that matters.** Agent graphs are tens of nodes, not millions of
  events. Replaying 20 map merges costs microseconds; replaying one duplicated LLM call costs
  money and correctness.
- **Framework-agnostic.** The adapter wraps node actions and does not care how the graph stores
  state, which is why the same idea will work for Spring AI and LangChain4j flows later.

## Consequences

- Node actions and edge conditions must be deterministic functions of the state. In practice this
  is already required for a resumable graph; it just becomes an explicit contract.
- Replay must be side-effect free. The adapter enforces this by construction: every node goes
  through `DurableSteps`, which never executes a journaled step.
- Long histories would be a problem. If a graph ever grows to thousands of steps, add snapshot
  acceleration on top - the journal stays the truth, the snapshot becomes a cache.
- Checkpoint rows are written per node for observability (timeline, diff), not for correctness.
  Losing them costs history, not safety.
