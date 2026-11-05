# Duragent

**Durable execution for AI agents. Crash-safe steps, human approvals and budget stops — without
changing how you build the graph.**

```python
from duragent import connect, run_step

conn = connect("agent.db")

answer = run_step(conn, run_id, "summarize", lambda: llm.invoke(prompt))
```

Call it again — new process, next morning, after a redeploy — and the lambda **does not run**.
The result stored the first time comes back instead.

## The problem

An agent that works in a demo falls apart in production. It reads a ticket, calls a model three
times, then issues a refund. Forty seconds later, right after the refund, the process restarts.

- Without Duragent: the pipeline re-runs from the top — the model is called again, **and the
  refund goes out twice**.
- With Duragent: every step's result is journaled the moment it finishes. On restart, finished
  steps replay from the journal (no model call, no charge) and only the unfinished step executes.

## Guarantees, stated honestly

| Situation | Behaviour |
|---|---|
| Step already succeeded | result replayed, function not called |
| Step failed | recorded as `FAILED`, the next call retries it |
| Process died mid-step | the lease expires, another worker re-executes it |
| Two workers, same step | one wins the claim, the other gets `StepInProgress` |
| Result not JSON-serialisable | rejected — the journal stores JSON |

The function runs **at least once**; its result is stored **at most once**. A crash between "the
side effect happened" and "the result was written" re-runs the function. Pass the step name
downstream as an idempotency key to close that window.

## Repository layout

| Path | What it is |
|---|---|
| [`python/`](python) | **the project**: `duragent`, the library you install |
| [`java/`](java) | a complete JVM implementation of the same design (steps, runs, approvals, budgets, workers, HTTP demo) |
| [`docs/adr/`](docs/adr) | the decisions that matter, in four short records |

The Python package is the one being developed. The Java tree is a reference implementation: same
four tables, same claim protocol, 54 tests — useful to read if you want the design without the
syntax of whichever language you are less comfortable with.

## Why not just use LangGraph's checkpointer

LangGraph checkpoints *where in the graph* you were. It does not record *whether a side effect
already happened*. Resuming from a checkpoint re-executes the node, so the refund can still go
out twice.

Duragent journals the node's **result** under a stable key and replays it. That is
[ADR 0001](docs/adr/0001-durable-step-as-the-primitive.md); the claim protocol is
[ADR 0002](docs/adr/0002-claim-by-primary-key.md); why replay beats snapshot restore is
[ADR 0003](docs/adr/0003-replay-instead-of-snapshot.md).

## Status

`0.1.0`, steps 1–4 of the Python port: durable steps, the LangGraph wrapper, approvals and
budgets — 20 tests, three runnable demos. Next: a worker queue so several processes can share
the load. The Java implementation is feature complete up to a demo HTTP API.

## 中文简介

Duragent 解决的是一个很具体的事故：agent 跑到一半进程崩了，重跑会把模型再调一遍、把退款再发一次。

它给每一步加一层“日志”：跑完就把结果写进数据库，重跑时直接读结果、不执行函数。再配上租约（死了的 worker 会被别人接管）、审批挂起（进程可以退出，几天后再继续）和预算（花到上限就停）。

`python/` 是主项目，`java/` 是同一套设计的 JVM 完整实现。

## License

Apache License 2.0.
