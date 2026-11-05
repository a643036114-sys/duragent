"""Spend control.

An agent in a retry loop can burn real money in minutes. A budget caps that:
charges are accepted only while they fit, and a charge that does not fit is
refused rather than billed.

    set_budget(conn, run_id, max_cost=0.50, max_steps=30)
    charge(conn, run_id, tokens=1200, cost=0.004)

Money is stored as integer micro-units, not as a float: 0.1 + 0.2 != 0.3, and a
ledger that drifts by fractions of a cent is a ledger nobody trusts.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

from duragent.steps import Abort

MICROS = 1_000_000


class BudgetExceeded(Abort):
    """A charge did not fit. Nothing was billed; the run should stop."""

    def __init__(self, run_id: str, limit: str, allowed: int, attempted: int):
        super().__init__(
            f"run {run_id} exceeded its {limit} budget: {attempted} > {allowed}"
        )
        self.run_id = run_id
        self.limit = limit
        self.allowed = allowed
        self.attempted = attempted


@dataclass(frozen=True)
class Budget:
    max_tokens: int | None
    max_cost_micros: int | None
    max_steps: int | None


@dataclass(frozen=True)
class Usage:
    tokens: int
    cost_micros: int
    steps: int

    @property
    def cost(self) -> float:
        return self.cost_micros / MICROS


def set_budget(conn, run_id: str, *, max_tokens: int | None = None,
               max_cost: float | None = None, max_steps: int | None = None,
               clock: Callable[[], float] = time.time) -> Budget:
    """Attach limits to a run.

    Calling this again replaces the limits and keeps the spend, which is how a
    cancelled run is resumed with a larger budget instead of restarting at zero.
    """
    if max_cost is not None and max_cost <= 0:
        raise ValueError("max_cost must be positive")
    now = clock()
    conn.execute(
        "INSERT INTO budget (run_id, max_tokens, max_cost_micros, max_steps,"
        " used_tokens, used_cost_micros, used_steps, started_at, updated_at)"
        " VALUES (?, ?, ?, ?, 0, 0, 0, ?, ?)"
        " ON CONFLICT(run_id) DO UPDATE SET"
        "   max_tokens = excluded.max_tokens,"
        "   max_cost_micros = excluded.max_cost_micros,"
        "   max_steps = excluded.max_steps,"
        "   updated_at = excluded.updated_at",
        (run_id, max_tokens, None if max_cost is None else _to_micros(max_cost), max_steps, now, now),
    )
    return budget_for(conn, run_id)  # type: ignore[return-value]


def budget_for(conn, run_id: str) -> Budget | None:
    row = conn.execute("SELECT * FROM budget WHERE run_id = ?", (run_id,)).fetchone()
    if row is None:
        return None
    return Budget(row["max_tokens"], row["max_cost_micros"], row["max_steps"])


def usage(conn, run_id: str) -> Usage | None:
    row = conn.execute("SELECT * FROM budget WHERE run_id = ?", (run_id,)).fetchone()
    if row is None:
        return None
    return Usage(row["used_tokens"], row["used_cost_micros"], row["used_steps"])


def charge(conn, run_id: str, *, tokens: int = 0, cost: float = 0.0, steps: int = 0,
           clock: Callable[[], float] = time.time) -> Usage | None:
    """Spend tokens, money and steps in one atomic statement.

    Returns None when the run has no budget (unlimited). Raises
    :class:`BudgetExceeded` when the charge would cross a limit - and in that
    case nothing is recorded.
    """
    if tokens < 0 or cost < 0 or steps < 0:
        raise ValueError("a charge cannot be negative")

    before = usage(conn, run_id)
    if before is None:
        return None

    cost_micros = _to_micros(cost)
    updated = conn.execute(
        "UPDATE budget"
        "   SET used_tokens = used_tokens + ?,"
        "       used_cost_micros = used_cost_micros + ?,"
        "       used_steps = used_steps + ?,"
        "       updated_at = ?"
        " WHERE run_id = ?"
        "   AND (max_tokens IS NULL OR used_tokens + ? <= max_tokens)"
        "   AND (max_cost_micros IS NULL OR used_cost_micros + ? <= max_cost_micros)"
        "   AND (max_steps IS NULL OR used_steps + ? <= max_steps)",
        (tokens, cost_micros, steps, clock(), run_id, tokens, cost_micros, steps),
    ).rowcount

    if updated:
        return usage(conn, run_id)
    raise _exceeded(conn, run_id, before, tokens, cost_micros, steps)


def _exceeded(conn, run_id: str, before: Usage, tokens: int, cost_micros: int,
              steps: int) -> BudgetExceeded:
    """Name the limit that refused the charge, so the error is actionable."""
    limits = budget_for(conn, run_id)
    if limits is not None:
        if limits.max_tokens is not None and before.tokens + tokens > limits.max_tokens:
            return BudgetExceeded(run_id, "tokens", limits.max_tokens, before.tokens + tokens)
        if limits.max_cost_micros is not None and before.cost_micros + cost_micros > limits.max_cost_micros:
            return BudgetExceeded(run_id, "cost", limits.max_cost_micros,
                                  before.cost_micros + cost_micros)
        if limits.max_steps is not None and before.steps + steps > limits.max_steps:
            return BudgetExceeded(run_id, "steps", limits.max_steps, before.steps + steps)

    # The database refused it but no single limit explains why: another worker
    # spent in between. Still a budget stop from this caller's point of view.
    return BudgetExceeded(run_id, "concurrent", 0, 0)


def _to_micros(cost: float) -> int:
    return round(cost * MICROS)
