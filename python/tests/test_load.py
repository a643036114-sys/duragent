"""The load benchmark, run small enough for CI."""

from __future__ import annotations

import sys
from pathlib import Path

BENCHMARKS = Path(__file__).resolve().parents[1] / "benchmarks"
sys.path.insert(0, str(BENCHMARKS))

import load_test  # noqa: E402


def test_concurrent_workers_complete_every_run_exactly_once():
    stats = load_test.run_load(runs=25, workers=4, steps_per_run=3, work_seconds=0)

    assert stats["completed"] == 25, "every run must finish"
    assert stats["steps"] == 75, "three steps per run, no more"
    assert stats["duplicated_runs"] == 0, "no run may execute a step twice"


def test_more_workers_than_runs_still_settles():
    stats = load_test.run_load(runs=5, workers=8, steps_per_run=2, work_seconds=0)

    assert stats["completed"] == 5
    assert stats["duplicated_runs"] == 0
