"""The real-model example, verified without a real model.

A tiny HTTP server stands in for the provider, so the example's request shape,
usage accounting and 鈥?most importantly 鈥?its replay behaviour are all tested.
"""

from __future__ import annotations

import importlib.util
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

pytest.importorskip("langgraph")
pytest.importorskip("httpx")

from duragent import connect, set_budget, usage  # noqa: E402

EXAMPLE = Path(__file__).resolve().parents[1] / "examples" / "real_llm_demo.py"
CALLS = {"n": 0}


class FakeProvider(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 - http.server API
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length))
        CALLS["n"] += 1
        payload = {
            "choices": [{"message": {"role": "assistant",
                                     "content": f"answer #{CALLS['n']} for {body['model']}"}}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
        }
        encoded = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *args) -> None:  # keep the test output clean
        pass


@pytest.fixture
def provider(monkeypatch):
    CALLS["n"] = 0
    server = HTTPServer(("127.0.0.1", 0), FakeProvider)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    base = f"http://127.0.0.1:{server.server_port}/v1"
    monkeypatch.setenv("DURAGENT_LLM_BASE_URL", base)
    monkeypatch.setenv("DURAGENT_LLM_API_KEY", "test-key")
    monkeypatch.setenv("DURAGENT_LLM_MODEL", "fake-model")

    yield server
    server.shutdown()
    server.server_close()


def load_example():
    spec = importlib.util.spec_from_file_location("real_llm_demo", EXAMPLE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_the_example_replays_instead_of_calling_the_model_again(provider):
    example = load_example()
    conn = connect()
    run_id = "run-llm-1"
    set_budget(conn, run_id, max_cost=1.0, max_tokens=10_000)
    crash = {"armed": True}

    example.CALLS["n"] = 0
    graph = example.build_graph(conn, run_id, crash)

    with pytest.raises(RuntimeError):
        graph.invoke({"topic": "durable execution"})

    assert CALLS["n"] == 2, "plan and write both called the model"
    first_spend = usage(conn, run_id).tokens

    final = graph.invoke({"topic": "durable execution"})

    assert final["published"] is True
    assert CALLS["n"] == 2, "the restart must not call the model again"
    assert usage(conn, run_id).tokens == first_spend, "and must not bill again"
    assert first_spend == 300, "two calls, 150 tokens each"


def test_the_usage_report_drives_the_budget(provider):
    example = load_example()
    conn = connect()
    run_id = "run-llm-2"
    set_budget(conn, run_id, max_cost=1.0)
    graph = example.build_graph(conn, run_id, {"armed": False})

    graph.invoke({"topic": "x"})

    spent = usage(conn, run_id)
    assert spent.tokens == 300
    assert spent.cost > 0
