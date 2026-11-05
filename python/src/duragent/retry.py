"""Retry policy: how long to wait before trying a failed step again.

The journal already records ``attempt`` and ``finished_at`` for every failed
step, so the schedule does not need a new column - the eligible time is computed
from what is already there:

    ready_at = finished_at + base * multiplier ** (attempt - 1)

That keeps a database upgrade unnecessary, which matters because this project
has no migrations yet.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import inf


class RetryLater(RuntimeError):
    """The step failed recently; its backoff has not elapsed yet.

    Not a failure of the run. The caller should leave the work alone until
    ``ready_in`` seconds have passed - a worker does that by keeping the lease,
    which expires on its own and becomes the timer.
    """

    def __init__(self, run_id: str, step_name: str, ready_in: float):
        super().__init__(
            f"{run_id}/{step_name} is backing off, retry in {ready_in:.2f}s"
        )
        self.run_id = run_id
        self.step_name = step_name
        self.ready_in = ready_in


class RetriesExhausted(RuntimeError):
    """The step used all its attempts. This one is terminal until an operator acts."""

    def __init__(self, run_id: str, step_name: str, attempts: int, last_error: str | None):
        super().__init__(
            f"{run_id}/{step_name} gave up after {attempts} attempts: {last_error}"
        )
        self.run_id = run_id
        self.step_name = step_name
        self.attempts = attempts
        self.last_error = last_error


@dataclass(frozen=True)
class Retry:
    """Exponential backoff.

    ``delay_for(1) == base``, then ``base * multiplier``, capped at ``max_delay``.
    """

    max_attempts: int = 3
    base: float = 0.5
    multiplier: float = 2.0
    max_delay: float = 30.0

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        if self.base < 0:
            raise ValueError("base must not be negative")
        if self.multiplier < 1:
            raise ValueError("multiplier must be at least 1")
        if self.max_delay < self.base:
            raise ValueError("max_delay must be at least base")

    def delay_for(self, attempt: int) -> float:
        """Seconds to wait after attempt number ``attempt`` failed (1-based)."""
        if attempt < 1:
            attempt = 1
        if self.multiplier == 1:
            delay = self.base
        else:
            delay = self.base * (self.multiplier ** (attempt - 1))
        return min(delay, self.max_delay, inf)
