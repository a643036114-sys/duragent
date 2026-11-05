"""Command line: look at what happened without opening a database shell.

    duragent inspect <run_id>
    duragent runs --limit 10

``--db`` defaults to ``demo.db`` and also accepts a PostgreSQL URL.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Sequence

from duragent.approvals import pending_approvals
from duragent.budgets import budget_for, usage
from duragent.db import connect
from duragent.fork import fork_run
from duragent.runs import get_run, list_runs
from duragent.steps import list_steps


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="duragent", description=__doc__)
    parser.add_argument("--db", default="demo.db", help="database path or postgresql:// URL")
    sub = parser.add_subparsers(dest="command", required=True)

    inspect = sub.add_parser("inspect", help="show one run in detail")
    inspect.add_argument("run_id")

    runs = sub.add_parser("runs", help="list recent runs")
    runs.add_argument("--limit", type=int, default=20)

    fork = sub.add_parser("fork", help="start a new run from another run's history")
    fork.add_argument("run_id")
    fork.add_argument("--at", dest="at_step", default=None,
                      help="step to execute again; everything before it is kept")
    fork.add_argument("--input", default=None, help="JSON that replaces the run input")

    args = parser.parse_args(argv)
    conn = connect(args.db)

    if args.command == "runs":
        return _list(conn, args.limit)
    if args.command == "fork":
        return _fork(conn, args.run_id, args.at_step, args.input)
    return _inspect(conn, args.run_id)


def _list(conn, limit: int) -> int:
    rows = list_runs(conn, limit)
    if not rows:
        print("no runs yet")
        return 0
    print(f"{'id':<10} {'status':<10} {'workflow':<20} created")
    for run in rows:
        print(f"{run.id[:8]:<10} {run.status:<10} {run.workflow:<20} {run.created_at:.0f}")
    return 0


def _inspect(conn, run_id: str) -> int:
    run = get_run(conn, run_id)
    if run is None:
        print(f"unknown run: {run_id}", file=sys.stderr)
        return 1

    print(f"run {run.id}")
    print(f"  workflow   {run.workflow}")
    print(f"  status     {run.status}")
    if run.not_before is not None:
        print(f"  not before {run.not_before:.0f}")
    print(f"  input      {run.input}")

    steps = list_steps(conn, run.id)
    print("\n  steps")
    if not steps:
        print("    (none)")
    for record in steps:
        extra = f"  {record.error_text}" if record.error_text else ""
        print(f"    {record.step_key:<24} {record.status:<10} attempt {record.attempt}{extra}")

    spent = usage(conn, run.id)
    if spent is not None:
        limits = budget_for(conn, run.id)
        cap = "unlimited" if limits is None or limits.max_cost_micros is None \
            else f"{limits.max_cost_micros / 1_000_000:.2f}"
        print(f"\n  budget     tokens={spent.tokens} cost={spent.cost:.4f} of {cap}"
              f" steps={spent.steps}")

    waiting = pending_approvals(conn, run.id)
    if waiting:
        print("\n  waiting for a human")
        for approval in waiting:
            print(f"    {approval.node:<24} {approval.reason}")
    return 0


def _fork(conn, run_id: str, at_step: str | None, raw_input: str | None) -> int:
    try:
        payload = json.loads(raw_input) if raw_input else None
    except json.JSONDecodeError as exc:
        print(f"--input is not valid JSON: {exc}", file=sys.stderr)
        return 1

    try:
        new_id = fork_run(conn, run_id, at_step=at_step, input=payload)
    except (KeyError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(new_id)
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
