"""A real model call behind the journal.

This is the honest version of the demo: an actual chat completion, priced from
the usage the provider reports, and a crash in the middle.

    export DURAGENT_LLM_API_KEY=sk-...
    python examples/real_llm_demo.py

Point it at any OpenAI-compatible endpoint:

    DURAGENT_LLM_BASE_URL=https://api.deepseek.com/v1
    DURAGENT_LLM_MODEL=deepseek-chat

Watch the token count after the crash: the first node is replayed, not re-billed.
"""

from __future__ import annotations

import os
import time
from typing import Any, TypedDict

import httpx
from langgraph.graph import END, START, StateGraph

from duragent import Journal, connect, new_run_id, set_budget, usage

BASE_URL = os.environ.get("DURAGENT_LLM_BASE_URL", "https://api.deepseek.com/v1")
API_KEY = os.environ.get("DURAGENT_LLM_API_KEY", os.environ.get("OPENAI_API_KEY", ""))
MODEL = os.environ.get("DURAGENT_LLM_MODEL", "deepseek-chat")

# Price per million tokens. The defaults are DeepSeek's published prices; override
# them for whatever you actually pay, or the budget numbers are fiction.
PRICE_IN = float(os.environ.get("DURAGENT_PRICE_IN", "0.27"))
PRICE_OUT = float(os.environ.get("DURAGENT_PRICE_OUT", "1.10"))

CALLS = {"n": 0}


class State(TypedDict, total=False):
    topic: str
    outline: str
    summary: str
    published: bool


def chat(messages: list[dict[str, str]], *, timeout: float = 60.0) -> tuple[str, int, float]:
    """One completion. Returns (text, tokens, cost) - cost from the reported usage."""
    CALLS["n"] += 1
    response = httpx.post(
        f"{BASE_URL}/chat/completions",
        headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"},
        json={"model": MODEL, "messages": messages, "temperature": 0.2},
        timeout=timeout,
    )
    response.raise_for_status()
    body = response.json()

    text = body["choices"][0]["message"]["content"]
    usage_block = body.get("usage") or {}
    tokens = int(usage_block.get("total_tokens", 0))
    cost = (
        int(usage_block.get("prompt_tokens", 0)) * PRICE_IN
        + int(usage_block.get("completion_tokens", 0)) * PRICE_OUT
    ) / 1_000_000
    print(f"  [llm] call {CALLS['n']}: {tokens} tokens, cost {cost:.6f}")
    return text, tokens, cost


def build_graph(conn, run_id: str, crash: dict[str, bool]):
    journal = Journal(conn, run_id)

    def plan(state: State) -> dict:
        text, tokens, cost = chat([
            {"role": "system", "content": "You write one-line outlines."},
            {"role": "user", "content": f"Outline an article about: {state['topic']}"},
        ])
        journal.charge(tokens=tokens, cost=cost)
        return {"outline": text}

    def write(state: State) -> dict:
        text, tokens, cost = chat([
            {"role": "system", "content": "You write a three-sentence summary."},
            {"role": "user", "content": f"Summarise this outline: {state['outline']}"},
        ])
        journal.charge(tokens=tokens, cost=cost)
        return {"summary": text}

    def publish(state: State) -> dict:
        if crash["armed"]:
            crash["armed"] = False
            raise RuntimeError("the process died while publishing")
        return {"published": True}

    builder = StateGraph(State)
    builder.add_node("plan", journal.durable("plan", plan, fingerprint=True, retry=None))
    builder.add_node("write", journal.durable("write", write, fingerprint=True))
    builder.add_node("publish", journal.durable("publish", publish))
    builder.add_edge(START, "plan")
    builder.add_edge("plan", "write")
    builder.add_edge("write", "publish")
    builder.add_edge("publish", END)
    return builder.compile()


def main() -> int:
    if not API_KEY:
        print("set DURAGENT_LLM_API_KEY (or OPENAI_API_KEY) to run this demo")
        return 1

    conn = connect(os.environ.get("DURAGENT_DB", "demo.db"))
    run_id = new_run_id()
    set_budget(conn, run_id, max_cost=0.50, max_steps=20)
    crash = {"armed": True}

    print(f"run id: {run_id}  model: {MODEL}  endpoint: {BASE_URL}\n")

    graph = build_graph(conn, run_id, crash)
    try:
        graph.invoke({"topic": "durable execution"})
    except RuntimeError as exc:
        print(f"crashed: {exc}")

    spent = usage(conn, run_id)
    print(f"after the crash: {CALLS['n']} model calls, {spent.tokens} tokens, {spent.cost:.4f}\n")

    print("-- restart --")
    final = graph.invoke({"topic": "durable execution"})
    spent = usage(conn, run_id)

    print(f"\nfinal state: published={final.get('published')}")
    print(f"model calls: {CALLS['n']}  (plan was replayed, not re-billed)")
    print(f"total spend: {spent.tokens} tokens, {spent.cost:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
