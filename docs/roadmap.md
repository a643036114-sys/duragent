# Roadmap

Eight milestones. Each one is shippable on its own and each one has a demo you can show
someone. The rule: no UI before milestone 6, no framework adapter before milestone 2.

## 1. Durable step ✅

The primitive everything else stands on.

- [x] `(run_id, step_key)` claim via primary key
- [x] result journaling + replay
- [x] lease, takeover of dead workers, attempt counter
- [x] failure recording + retry
- [x] schema verified on H2 and PostgreSQL 16
- [x] crash/resume demo

## 2. Run + checkpoint (LangGraph4j adapter) ✅

Give the library a notion of a *run* and plug it into LangGraph4j's node execution.

- [x] `run` table: status, input, graph state, cursor, optimistic version
- [x] `DurableGraph` — wrap `StateGraph` nodes so every node is a durable step
- [x] checkpoint per node (timeline for diff/replay)
- [x] resume by replaying the journal instead of restoring a snapshot (ADR 0003)
- [x] test: 3-node agent that dies at node 2, then resumes: node 1 replays, node 2 retries,
      node 3 runs once
- [ ] demo: 5-node agent with a real JVM kill (not an exception) + `docker compose`

## 3. Approvals (human in the loop) ✅

- [x] `request` parks the run as `SUSPENDED` — no thread sleeps
- [x] `approve` / `reject` resume or fail the run; first decision wins
- [x] idempotent requests: one `PENDING` row per `(run, node)`
- [x] `durableWithApproval` node wrapper; an approval pause is not a step failure
- [x] test: refund node parks, manager approves, refund executes once
- [ ] timeout policy per approval (approve / reject / escalate)
- [ ] HTTP endpoints so a UI can list pending approvals

## 4. Budgets and guardrails ✅

- [x] token, money, step-count and wall-clock budgets per run
- [x] atomic charge: one conditional `UPDATE`, so two workers cannot spend the last cent twice
- [x] hard limit aborts the run as `CANCELED` with state preserved
- [x] abort releases the step claim instead of poisoning the journal (ADR 0004)
- [x] `raise` + `resume` finishes the run without restarting it from zero
- [x] metering: one step per executed node, token/money charged by the node itself
- [ ] soft limit (warn + emit an event) as opposed to the hard stop
- [ ] per-node cost attribution table for a spend breakdown

## 5. Scheduler and workers

- [ ] claimable run queue (`FOR UPDATE SKIP LOCKED` where available)
- [ ] N workers, at-least-once run dispatch
- [ ] heartbeat and re-dispatch on worker death

## 6. Replay and diff CLI

- [ ] `duragent replay <run_id>` — print the recorded timeline
- [ ] `duragent diff <run_a> <run_b>` — step-by-step output diff
- [ ] `duragent inspect <run_id>` — why did this step run twice?

## 7. Spring Boot starter

- [ ] auto-configuration, `application.yml` properties
- [ ] Actuator endpoint: runs in flight, steps stuck, budget burn
- [ ] demo: `docker compose up` → API + dashboard JSON

## 8. Open-source launch

- [ ] README with an animated demo gif
- [ ] 3 runnable examples (support ticket, long-form generation, refund approval)
- [ ] launch posts: HN, r/LocalLLaMA, 掘金/知乎/V2EX
- [ ] 200 stars in 30 days, 3 external issues, 1 external PR
