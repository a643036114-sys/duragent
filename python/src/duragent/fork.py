"""Fork a run: keep the history up to a step, then go a different way.

This is the feature that a snapshot-based runtime cannot offer. Because the
journal stores every step's *result*, a fork is just "copy the rows that already
succeeded into a new run id" - the new run replays them for free and only the
step you forked at (and everything after it) executes again.

    fork_run(conn, run_id, at_step="write")     # re-run "write" and later
    fork_run(conn, run_id)                      # clone: nothing re-executes

A fork is not one transaction. A crash halfway through the copy leaves a shorter
prefix, which is still coherent: the next step simply re-executes. The alternative
would be a half-written run row that looks complete, which is worse.
"""

from __future__ import annotations

import time
from typing import Any, Callable

from duragent.budgets import budget_for, set_budget
from duragent.runs import enqueue, get_run
from duragent.steps import list_steps

INSERT_STEP = """
INSERT INTO step_record
    (run_id, step_key, status, attempt, output_json, error_text,
     started_at, finished_at, lease_until, input_hash)
VALUES (?, ?, 'SUCCEEDED', ?, ?, NULL, ?, ?, 0, ?)
"""


def fork_run(
    conn,
    source_run_id: str,
    *,
    at_step: str | None = None,
    input: dict[str, Any] | None = None,
    clock: Callable[[], float] = time.time,
) -> str:
    """Create a new run that inherits the history of ``source_run_id``.

    ``at_step`` names the step that should run **again**: everything that
    succeeded before it is copied, the step itself and everything after it will
    execute. ``None`` clones the run as it stands, which replays entirely.

    ``input`` replaces the run's input payload. It only affects steps that
    actually execute, so it is most useful together with ``at_step`` pointing at
    the first node.
    """
    source = get_run(conn, source_run_id)
    if source is None:
        raise KeyError(f"unknown run: {source_run_id}")

    succeeded = [record for record in list_steps(conn, source_run_id)
                 if record.status == "SUCCEEDED"]

    if at_step is not None:
        position = next((index for index, record in enumerate(succeeded)
                         if record.step_key == at_step), None)
        if position is None:
            raise ValueError(
                f"step '{at_step}' never succeeded in run {source_run_id}; "
                "only a completed step can be a fork point"
            )
        history = succeeded[:position]
    else:
        history = succeeded

    new_run_id = enqueue(conn, source.workflow, source.input if input is None else input,
                         forked_from=source_run_id, clock=clock)

    for record in history:
        conn.execute(
            INSERT_STEP,
            (new_run_id, record.step_key, record.attempt, record.output_json,
             record.started_at, record.finished_at, _carried_fingerprint(record, input)),
        )

    _copy_budget_limits(conn, source_run_id, new_run_id, clock)
    return new_run_id


def _carried_fingerprint(record, new_input: dict[str, Any] | None) -> str | None:
    """Keep the input hash, unless the input was replaced.

    The hash exists to notice that a replay is about to reuse a result computed
    from a *different* input. After a fork with a new payload that is exactly
    what the inherited rows are, by design, so the hash is dropped for them.
    """
    return None if new_input is not None else record.input_hash


def _copy_budget_limits(conn, source_run_id: str, new_run_id: str,
                        clock: Callable[[], float]) -> None:
    """A fork gets the same limits and a fresh ledger: it is a new attempt, not a refund."""
    limits = budget_for(conn, source_run_id)
    if limits is None:
        return
    max_cost = None if limits.max_cost_micros is None else limits.max_cost_micros / 1_000_000
    set_budget(conn, new_run_id, max_tokens=limits.max_tokens,
               max_cost=max_cost, max_steps=limits.max_steps, clock=clock)
