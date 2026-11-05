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

## 2. Run + checkpoint (LangGraph4j adapter)

Give the library a notion of a *run* and plug it into LangGraph4j's node execution.

- [ ] `run` table: status, input, graph state, version
- [ ] `DurableGraph` — wrap `StateGraph` nodes so every node is a durable step
- [ ] checkpoint per super-step (graph state snapshot, JSON)
- [ ] resume a run from its last checkpoint
- [ ] demo: 5-node agent, kill the JVM at node 3, resume from node 3

## 3. Approvals (human in the loop)

- [ ] `suspend(runId, reason)` — no thread sleeps, the run is parked in the database
- [ ] `approve(runId, decision)` — resumes from the parked step
- [ ] timeout policy per approval (approve / reject / escalate)
- [ ] demo: refund flow that waits two days for a manager

## 4. Budgets and guardrails

- [ ] token, money, step-count and wall-clock budgets per run
- [ ] soft limit (warn + record) vs hard limit (abort with a preserved state)
- [ ] per-step cost attribution
- [ ] demo: a loop that burns money, stopped at $0.50

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
