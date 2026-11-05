# Duragent

**Durable execution for AI agents, native to the JVM.**

Your agent works in a demo and falls apart in production. A node throws halfway through, the
process restarts, and the pipeline re-runs from the top: the model is called again, the ticket
is answered twice, the customer is charged twice. `Duragent` is the layer that makes an agent
step survive a crash, a deploy or a two-day human approval wait.

```java
var steps = new DurableSteps(new JdbcStepStore(dataSource));

UUID runId = UUID.randomUUID();

String summary = steps.run(runId, "summarize-ticket", String.class,
        () -> llm.complete(prompt));   // 800ms, $0.004, can return 529
```

Call it again — in the same process, after a restart, from another machine — and the lambda
**does not execute**. The persisted result is replayed instead.

## With LangGraph4j

```java
var nodes = new DurableNodeActions(runId, steps, runs);

var graph = new StateGraph<AgentState>(AgentState::new)
        .addNode("plan",      nodes.durable("plan",      planAction))
        .addNode("summarize", nodes.durable("summarize", summarizeAction))
        .addNode("publish",   nodes.durable("publish",   publishAction))
        .addEdge(START, "plan")
        .addEdge("plan", "summarize")
        .addEdge("summarize", "publish")
        .addEdge("publish", END)
        .compile();

new DurableGraph<>(graph, runs, runId).invoke(Map.of("ticket", "T-1"));
```

If the JVM dies inside `summarize`, the next invoke replays `plan` from the journal (no model
call, no cost), retries `summarize`, and runs `publish` once. See
[ADR 0003](docs/adr/0003-replay-instead-of-snapshot.md) for why replay beats snapshot restore.

## Modules

| Module | What it is |
|---|---|
| `duragent-core` | durable steps, runs, checkpoints, JDBC stores. No framework dependency. |
| `duragent-langgraph4j` | wraps LangGraph4j node actions, nothing else. |

## Why another layer

LangGraph (and LangChain4j / Spring AI on the JVM) model the *control flow* of an agent: nodes,
edges, state. They do not model the *side effects*: a node that calls a model, charges a card or
sends a mail has no idea whether it already ran. The official LangGraph Platform solves this for
Python and JavaScript, as a closed-source commercial runtime.

Duragent is the open, framework-agnostic, JVM-native piece:

| Concern | Usual answer | What Duragent does |
|---|---|---|
| Re-run protection | exactly-once is a myth; use idempotency keys | persists each step result under a stable key |
| Crash recovery | worker leases + takeover | `lease_expires_at_ms`, conditional `UPDATE` |
| Long human approvals | durable timers | run parks as `SUSPENDED`, no thread held |
| Cost control | budgets | planned: token / money / step / wall-clock caps |

## How a step executes

```
        ┌─────────────┐   no record      ┌──────────────────────┐
call ──▶│ SELECT step │─────────────────▶│ INSERT claim (PK)    │
        └─────────────┘                  └──────────┬───────────┘
               │ record exists                      │ execute body
               ▼                                    ▼
     SUCCEEDED ──▶ replay stored JSON     persist output, status=SUCCEEDED
     FAILED    ──▶ retry (attempt+1)
     RUNNING   ──▶ lease alive? block  :  take over (attempt+1)
```

The primary key `(run_id, step_key)` is the lock. The `UPDATE ... WHERE status = 'RUNNING'`
row count is the ownership check. No `SELECT FOR UPDATE`, no advisory locks, no ORM — the same
four statements run on H2, PostgreSQL and MySQL.

## Guarantees, stated honestly

* The body executes **at least once**. Its result is stored **at most once**.
* A crash in the window between "side effect happened" and "result committed" re-runs the body.
  Pass the step key downstream as an idempotency key to close that window — payment APIs, mail
  providers and most LLM gateways accept one.
* Two live workers never both execute the same step; the loser fails fast with
  `StepInProgressException` rather than blocking a thread.
* Replay requires deterministic node actions and edge conditions. That is a requirement of any
  resumable graph, but it is written down here instead of assumed.

## Quickstart

```bash
mvn test                              # 18 tests on embedded H2, no setup
mvn test -Dtest=CrashResumeDemoTest   # crash + resume in the console
```

Against a real PostgreSQL:

```bash
mvn test -Dtest=PostgresCompatibilityTest \
  -Dduragent.pg.url=jdbc:postgresql://127.0.0.1:5432/duragent \
  -Dduragent.pg.user=postgres -Dduragent.pg.password=123456
```

> This repository ships a project-local `.mvn/settings.xml` because the machine-wide Maven
> mirror is unreachable here. Delete `.mvn/maven.config` if your environment resolves Maven
> Central normally.

## Status

`0.1.0-SNAPSHOT`, milestones 1–2 of 8. Durable steps, runs, checkpoints and the LangGraph4j
adapter work and are tested against H2 and PostgreSQL 16. Not production ready: no approvals,
no budgets, no worker scheduler yet.

See [`docs/roadmap.md`](docs/roadmap.md) and the decision records in [`docs/adr/`](docs/adr/).

## 中文简介

Duragent 是一个跑在 JVM 上的 Agent 生产化运行时。它解决的问题是：LangGraph 这类框架管的是
"图怎么走"，但没管"这一步的副作用有没有重复执行"。进程崩了、机器重启了、审批卡了两天，
Duragent 能把每一步的结果从数据库里重放出来，而不是重新调用模型、重新扣款。

核心是一个 `(run_id, step_key)` 主键 + 条件 `UPDATE` 实现的抢占协议，四条 SQL 同时兼容
H2 / PostgreSQL / MySQL，不绑框架、不绑 ORM。

## License

Apache License 2.0.
