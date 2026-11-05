"""HTTP surface.

Deliberately small: five endpoints. The interesting part of the project is not
the web layer, and a bigger API would only hide that.

    app = create_app(conn, {"summary": run_summary}, autostart=True)
    uvicorn.run(app)

Endpoints:

    POST /api/runs                       {workflow, input}
    GET  /api/runs/{run_id}              status + steps + budget + approvals
    GET  /api/runs/{run_id}/approvals    pending approvals
    POST /api/approvals/{id}/approve     {by, comment}
    POST /api/approvals/{id}/reject      {by, comment}
"""

from __future__ import annotations

import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, PlainTextResponse
from pydantic import BaseModel, Field

from duragent.approvals import approve, pending_approvals, reject
from duragent.budgets import budget_for, set_budget, usage
from duragent.runs import enqueue, get_run, list_runs
from duragent.steps import list_steps
from duragent.worker import Handler, Worker

INDEX_HTML = Path(__file__).with_name("web") / "index.html"


class CreateRunRequest(BaseModel):
    workflow: str
    input: dict[str, Any] = Field(default_factory=dict)
    max_cost: float | None = None
    max_steps: int | None = None


class DecisionRequest(BaseModel):
    by: str = "http"
    comment: str | None = None


def create_app(
    conn,
    workflows: dict[str, Handler],
    *,
    worker_id: str = "api-worker",
    autostart: bool = False,
    poll_interval: float = 0.2,
    lease: float = 30.0,
    heartbeat_every: float = 5.0,
) -> FastAPI:
    """Build the app around one connection and a set of workflow handlers.

    ``autostart`` runs a poll loop in a daemon thread, which is what makes the
    demo feel alive: POST a run and watch it move. Tests leave it off and drive
    the worker themselves.
    """
    worker = Worker(conn, worker_id, workflows, lease=lease, heartbeat_every=heartbeat_every)
    stop = threading.Event()

    def poll_loop() -> None:
        while not stop.is_set():
            try:
                if not worker.poll_once():
                    time.sleep(poll_interval)
            except Exception:  # one broken run must not kill the poll loop
                time.sleep(poll_interval)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if autostart:
            threading.Thread(target=poll_loop, name="duragent-poll", daemon=True).start()
        yield
        stop.set()
        worker.close()

    app = FastAPI(title="duragent", version="0.1.0", lifespan=lifespan)
    app.state.worker = worker
    app.state.conn = conn

    @app.get("/", response_class=HTMLResponse)
    def index() -> str:
        """The one-page console. No build step, no bundler, no framework."""
        return INDEX_HTML.read_text(encoding="utf-8")

    @app.get("/api/runs")
    def read_runs(limit: int = 20) -> list[dict[str, Any]]:
        return [
            {"id": run.id, "workflow": run.workflow, "status": run.status,
             "created_at": run.created_at}
            for run in list_runs(conn, max(1, min(limit, 200)))
        ]

    @app.post("/api/runs", status_code=202)
    def create_run(body: CreateRunRequest) -> dict[str, Any]:
        if body.workflow not in workflows:
            raise HTTPException(status_code=400, detail=f"unknown workflow: {body.workflow}")
        run_id = enqueue(conn, body.workflow, body.input)
        if body.max_cost is not None or body.max_steps is not None:
            set_budget(conn, run_id, max_cost=body.max_cost, max_steps=body.max_steps)
        return {"id": run_id, "status": "PENDING"}

    @app.get("/api/runs/{run_id}")
    def read_run(run_id: str) -> dict[str, Any]:
        run = _require_run(conn, run_id)
        return {
            "id": run.id,
            "workflow": run.workflow,
            "status": run.status,
            "steps": [
                {
                    "step": record.step_key,
                    "status": record.status,
                    "attempt": record.attempt,
                    "error": record.error_text,
                }
                for record in list_steps(conn, run_id)
            ],
            "budget": _budget_view(conn, run_id),
            "approvals": [_approval_view(a) for a in pending_approvals(conn, run_id)],
        }

    @app.get("/api/runs/{run_id}/approvals")
    def read_approvals(run_id: str) -> list[dict[str, Any]]:
        _require_run(conn, run_id)
        return [_approval_view(a) for a in pending_approvals(conn, run_id)]

    @app.post("/api/approvals/{approval_id}/approve")
    def approve_one(approval_id: str, body: DecisionRequest | None = None) -> dict[str, Any]:
        decision = body or DecisionRequest()
        return _approval_view(_decide(approve, conn, approval_id, decision))

    @app.post("/api/approvals/{approval_id}/reject")
    def reject_one(approval_id: str, body: DecisionRequest | None = None) -> dict[str, Any]:
        decision = body or DecisionRequest()
        return _approval_view(_decide(reject, conn, approval_id, decision))

    @app.get("/api/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/metrics", response_class=PlainTextResponse)
    def metrics() -> str:
        """Prometheus text format. Small on purpose: three questions an operator asks."""
        lines = ["# TYPE duragent_runs gauge"]
        for row in conn.execute("SELECT status, count(*) AS n FROM run GROUP BY status").fetchall():
            lines.append(f'duragent_runs{{status="{row["status"]}"}} {row["n"]}')

        lines.append("# TYPE duragent_steps gauge")
        for row in conn.execute(
            "SELECT status, count(*) AS n FROM step_record GROUP BY status"
        ).fetchall():
            lines.append(f'duragent_steps{{status="{row["status"]}"}} {row["n"]}')

        spent = conn.execute(
            "SELECT coalesce(sum(used_cost_micros), 0) AS total FROM budget"
        ).fetchone()
        lines.append("# TYPE duragent_spend_micros counter")
        lines.append(f"duragent_spend_micros {spent['total']}")
        return "\n".join(lines) + "\n"

    return app


def _require_run(conn, run_id: str):
    run = get_run(conn, run_id)
    if run is None:
        raise HTTPException(status_code=404, detail=f"unknown run: {run_id}")
    return run


def _decide(decide, conn, approval_id: str, decision: DecisionRequest):
    try:
        return decide(conn, approval_id, by=decision.by, comment=decision.comment)
    except KeyError:
        raise HTTPException(status_code=404, detail=f"unknown approval: {approval_id}") from None


def _approval_view(approval) -> dict[str, Any]:
    return {
        "id": approval.id,
        "run_id": approval.run_id,
        "node": approval.node,
        "reason": approval.reason,
        "status": approval.status,
        "decided_by": approval.decided_by,
        "expires_at": approval.expires_at,
    }


def _budget_view(conn, run_id: str) -> dict[str, Any] | None:
    spent = usage(conn, run_id)
    if spent is None:
        return None
    limits = budget_for(conn, run_id)
    return {
        "tokens": spent.tokens,
        "cost": spent.cost,
        "steps": spent.steps,
        "max_cost": None if limits is None or limits.max_cost_micros is None
        else limits.max_cost_micros / 1_000_000,
        "max_steps": None if limits is None else limits.max_steps,
    }
