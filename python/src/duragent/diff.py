"""Compare two runs step by step.

The point of forking a run is to change one thing and see what happens. That only
pays off if you can see *what* changed, so a fork without a diff is half a
feature.

    diff = diff_runs(conn, before, after)
    print(format_diff(diff))
"""

from __future__ import annotations

import difflib
import json
from dataclasses import dataclass

from duragent.budgets import usage
from duragent.runs import get_run
from duragent.steps import list_steps


@dataclass(frozen=True)
class StepDiff:
    step: str
    a_status: str | None
    b_status: str | None
    changed: bool
    lines: tuple[str, ...]

    def summary(self) -> str:
        if self.a_status is None:
            return "only in b"
        if self.b_status is None:
            return "only in a"
        return "changed" if self.changed else "same"


@dataclass(frozen=True)
class RunDiff:
    a: str
    b: str
    a_status: str
    b_status: str
    steps: tuple[StepDiff, ...]
    a_tokens: int
    b_tokens: int
    a_cost: float
    b_cost: float

    @property
    def changed_steps(self) -> tuple[StepDiff, ...]:
        return tuple(step for step in self.steps if step.changed)


def diff_runs(conn, a_run_id: str, b_run_id: str) -> RunDiff:
    """Compare the journals of two runs, matched by step name."""
    a = get_run(conn, a_run_id)
    b = get_run(conn, b_run_id)
    if a is None:
        raise KeyError(f"unknown run: {a_run_id}")
    if b is None:
        raise KeyError(f"unknown run: {b_run_id}")

    a_steps = {record.step_key: record for record in list_steps(conn, a_run_id)}
    b_steps = {record.step_key: record for record in list_steps(conn, b_run_id)}

    order = list(a_steps) + [name for name in b_steps if name not in a_steps]
    rows = []
    for step in order:
        left = a_steps.get(step)
        right = b_steps.get(step)
        changed = _changed(left, right)
        lines = tuple(_unified(step, left, right)) if changed else ()
        rows.append(StepDiff(
            step=step,
            a_status=None if left is None else left.status,
            b_status=None if right is None else right.status,
            changed=changed,
            lines=lines,
        ))

    a_usage = usage(conn, a_run_id)
    b_usage = usage(conn, b_run_id)

    return RunDiff(
        a=a_run_id,
        b=b_run_id,
        a_status=a.status,
        b_status=b.status,
        steps=tuple(rows),
        a_tokens=0 if a_usage is None else a_usage.tokens,
        b_tokens=0 if b_usage is None else b_usage.tokens,
        a_cost=0.0 if a_usage is None else a_usage.cost,
        b_cost=0.0 if b_usage is None else b_usage.cost,
    )


def format_diff(result: RunDiff) -> str:
    """A terminal rendering: one row per step, then the changed lines."""
    out = [
        f"a  {result.a}  {result.a_status}",
        f"b  {result.b}  {result.b_status}",
        "",
    ]
    width = max((len(step.step) for step in result.steps), default=4)

    for step in result.steps:
        out.append(
            f"  {step.step:<{width}}  {step.summary():<10}"
            f"{step.a_status or '-':>10} -> {step.b_status or '-':<10}"
        )
        for line in step.lines:
            out.append(f"    {line}")

    if not result.steps:
        out.append("  (no steps in either run)")

    if result.a_tokens or result.b_tokens:
        out.append("")
        out.append(f"  tokens {result.a_tokens} -> {result.b_tokens}"
                   f"   cost {result.a_cost:.4f} -> {result.b_cost:.4f}")
    return "\n".join(out)


def _changed(left, right) -> bool:
    if left is None or right is None:
        return True
    if left.status != right.status:
        return True
    return _render(left) != _render(right)


def _render(record) -> str:
    """Pretty JSON when the output is JSON, raw text otherwise."""
    if record is None or record.output_json is None:
        return ""
    try:
        return json.dumps(json.loads(record.output_json), indent=2,
                          sort_keys=True, ensure_ascii=False)
    except (TypeError, ValueError):
        return record.output_json


def _unified(step: str, left, right) -> list[str]:
    return list(difflib.unified_diff(
        _render(left).splitlines(),
        _render(right).splitlines(),
        fromfile=f"a/{step}",
        tofile=f"b/{step}",
        lineterm="",
    ))
