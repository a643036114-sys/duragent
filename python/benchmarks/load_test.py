"""How does the runtime behave with several workers and a full queue?

    python benchmarks/load_test.py --runs 200 --workers 4

The numbers are not a benchmark against anything; they answer one question -
do runs complete exactly once when threads compete for the same database?
"""

from __future__ import annotations

import argparse
import threading
import time
from typing import Any

from duragent import COMPLETED, Journal, Worker, connect, enqueue, get_run


def run_load(*, runs: int = 100, workers: int = 4, steps_per_run: int = 3,
             work_seconds: float = 0.001, url: str = ":memory:") -> dict[str, Any]:
    conn = connect(url)
    run_ids = [enqueue(conn, "bench", {"i": i}) for i in range(runs)]

    def handler(run_id: str, payload: dict) -> None:
        journal = Journal(conn, run_id)
        for index in range(steps_per_run):
            journal.durable(
                f"step-{index}",
                lambda _state, index=index: (_sleep(work_seconds), {"step": index})[1],
            )({})

    def loop() -> None:
        with Worker(conn, f"worker-{threading.get_ident()}", {"bench": handler},
                    lease=30, heartbeat_every=5, sweep_every=None) as worker:
            worker.drain()

    started = time.perf_counter()
    threads = [threading.Thread(target=loop) for _ in range(workers)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    elapsed = time.perf_counter() - started

    statuses = [get_run(conn, run_id).status for run_id in run_ids]
    completed = sum(1 for status in statuses if status == COMPLETED)

    rows = conn.execute(
        "SELECT run_id, count(*) AS n FROM step_record GROUP BY run_id"
    ).fetchall()
    steps = sum(row["n"] for row in rows)
    duplicated = sum(1 for row in rows if row["n"] != steps_per_run)

    return {
        "runs": runs,
        "completed": completed,
        "steps": steps,
        "expected_steps": runs * steps_per_run,
        "duplicated_runs": duplicated,
        "workers": workers,
        "seconds": round(elapsed, 3),
        "runs_per_second": round(runs / elapsed, 1) if elapsed else 0.0,
    }


def _sleep(seconds: float) -> None:
    if seconds:
        time.sleep(seconds)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=200)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--steps", type=int, default=3)
    parser.add_argument("--db", default=":memory:")
    args = parser.parse_args()

    stats = run_load(runs=args.runs, workers=args.workers,
                     steps_per_run=args.steps, url=args.db)
    for key, value in stats.items():
        print(f"{key:>16}: {value}")
    return 0 if stats["completed"] == stats["runs"] and stats["duplicated_runs"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
