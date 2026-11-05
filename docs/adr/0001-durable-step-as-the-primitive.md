# ADR 0001 — The durable step is the primitive

*Status: accepted · 2026-11-05*

## Context

We want an agent runtime where a crash never re-charges a customer or re-spends a model call.
Two designs were on the table:

1. **Workflow-level checkpointing.** Snapshot the whole graph state after every super-step and
   resume from the last snapshot (what LangGraph's checkpointer does).
2. **Step-level effect journaling.** Persist the *result* of every side effect under a stable key,
   and replay results instead of re-running work.

## Decision

Design 2, with design 1 layered on top later.

## Why

- Checkpointing gives you "resume from node N", but node N still re-executes and re-triggers its
  side effect. The expensive, irreversible part is never covered.
- Step journaling composes: it does not care whether the caller is a graph, a `for` loop, a Spring
  `@Scheduled` job or a plain `main`. That keeps the core framework-agnostic, which is the whole
  point of the project.
- It is also the only version that gives a useful replay: you can diff the stored outputs of two
  runs, because they are values, not opaque checkpoints.

## Consequences

- The caller must name each step stably. Renaming a step changes its identity and re-executes it.
  Documented in `StepKeys`; needs a changelog note on renames.
- Loops need an occurrence discriminator (`step#1`, `step#2`) until the runner owns that
  bookkeeping. This is a known limitation of step 1.
- Graph state checkpointing still has to be built (step 2) — it is additive, not replaced.
