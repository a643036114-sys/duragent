# Launch material

Drafts and answers, so the launch is a copy-paste rather than a blank page.

## The one line

| Where | Text |
|---|---|
| English | **Durable execution for LangGraph agents.** A crash replays finished steps instead of repeating them. |
| 中文 | **让你的 agent 不会重复退款。** 每一步的结果都落库，崩了重放而不是重跑。 |
| Elevator | LangGraph decides which node runs next. It does not know whether that node already charged the customer. This does. |

Lead with the concrete accident, not the category. "Durable execution" is a term only people who
already want it will search for; "the refund went out twice" is something everyone recognises.

## Show HN

> **Show HN: Durable execution for LangGraph agents, in one file of SQL**
>
> LangGraph checkpoints tell you which node you reached. They do not tell you whether that node
> already sent the email. Resume from a checkpoint and the node runs again, side effects included.
>
> This is a small library that journals each step's *result* under `(run_id, step_name)` and
> returns it on replay. A crash in the middle of an agent costs one node, not the whole run, and
> the model calls that already happened are not billed twice.
>
> It also covers the things that bite after that: approvals that park a run without holding a
> thread, a budget ledger that stops a runaway loop at 0.08 of 0.10, and workers that take over
> from a dead peer after its lease expires.
>
> Runs on SQLite out of the box and Postgres with a URL. No new infrastructure, no server to
> deploy. `pip install duragent`, or read `steps.py` — it is 200 lines.
>
> Forking is the part I have not seen elsewhere: because the journal keeps values rather than
> opaque snapshots, you can fork a run at any completed step, change the input, and diff the two
> branches. A snapshot-based runtime cannot do that.
>
> Would like feedback from anyone running agents against real money or real customers.

Avoid: "revolutionary", "10x", "blazing". State the mechanism, let the reader judge.

## 掘金 / 知乎

> **我写了个库，让 agent 崩了不会重复扣钱**
>
> 先说事故：一个客服 agent，五步流程，最后一步是调支付接口退款。跑到第 4 步刚退完，进程重启了。
> 重跑一遍——模型又调了一次，退款又发了一次。
>
> LangGraph 的 checkpointer 解决的是"从哪个节点继续"，它不知道这个节点**已经执行过了**。
> 从断点恢复，节点还是会重新跑，副作用照样发生。
>
> 所以我做了 Duragent：每一步跑完，把**结果**写进数据库，按 `(run_id, 步骤名)` 存。
> 重跑时先查日志，有结果就直接返回，**不执行**。崩溃的代价从"整条链路"降到"一个节点"。
>
> 顺手把后面会咬人的几件事也做了：
> - 审批：大额操作挂起，进程可以退出，几天后批了再继续（不占线程）
> - 预算：死循环里的模型调用，花到 0.08/0.10 就停，还能提额续跑
> - 租约：worker 挂了，另一个接手，已完成的步骤从日志回放
> - 指纹：改了 prompt 却返回旧结果——这是 agent 项目特有的坑，直接报错
>
> 默认 SQLite，给个 URL 就换 Postgres，不用起新服务。`python/src/duragent/steps.py` 两百行，
> 十分钟能读完。
>
> 有一个我觉得挺有意思的能力：**从任意一步分叉**。因为存的是结果不是快照，
> 你可以把某个 run 在第 3 步的现场拿出来，改输入，跑另一条分支，然后 diff 两条分支的每一步输出。
> 快照式的引擎做不到这个。
>
> GitHub 搜 duragent。有在跑真实业务的，欢迎来骂。

## V2EX（一句话 + 链接）

> 做了个 Python 库：给 LangGraph 的每一步加日志，崩了重放而不是重跑，避免重复调模型/重复扣钱。
> 顺带支持审批挂起、预算熔断、多 worker 抢任务。默认 SQLite，可换 Postgres。
> 还有一个"从任意一步分叉 + diff 两条分支"的调试功能。求轻拍。

## 会被问到的问题，先准备好

**Q: 为什么不用 LangGraph Platform？**
A: 它是闭源的商业运行时，自托管要企业授权；而且它解决"跑得稳"，不解决"看得清"。这个库零基础设施，
SQLite 起步，你的 Postgres 就够。如果你已经在用 Platform 且满意，不需要这个。

**Q: 为什么不用 Temporal？**
A: Temporal 是工业级的，但你要养一个集群，而且 agent 的图是几十个节点、跑几十秒的东西，
为它上 Temporal 不划算。这个库是"在一个进程里拿到 Temporal 的语义"。

**Q: 是 exactly-once 吗？**
A: 不是，也不可能有。函数**至少执行一次**，结果**至多保存一次**。"副作用发生"到"结果落库"之间崩溃仍会重跑，
所以提供 `journal.key()` 给你当幂等键传给下游。所有声称 exactly-once 的都在偷换概念。

**Q: 和 DBOS 有什么区别？**
A: 思路接近（都把 durable execution 做成库），DBOS 更通用；这个更贴 agent：节点级包装、
审批、预算、分叉对比都是按 agent 的形状做的。

**Q: 性能？**
A: 每步两条 SQL。200 个 run / 4 个 worker / 600 步，SQLite 上 0.23 秒，0 重复。瓶颈在模型，不在日志。

## 发布前检查清单

- [ ] 仓库公开，README 顶部的 GIF 能显示（`docs/demo.gif`，78KB）
- [ ] `pip install -e ".[dev,api,postgres,langgraph]"` 干净跑通
- [ ] CI 绿（3.11 + 3.13 + Postgres）
- [ ] 打 tag `v0.1.0`，写 release notes
- [ ] `docs/status.md` 里的"已知限制"是最新的（别让人踩坑才发现）
- [ ] 中文帖子里放 GIF，英文帖子里放 GIF —— 视频比文字快十倍
- [ ] 发布后 48 小时内回每条 issue/评论，前 10 个 star 的人决定了后面 100 个

## 节奏

一天内发完，别分散：

1. 早上：掘金 + 知乎（中文，故事先行）
2. 中午：V2EX
3. 晚上（美东上午）：Show HN + r/LocalLLaMA
4. 第二天：把收到的真实问题补进 README 的 FAQ

不要一次改十个东西再发。发出去之后，**第一个 issue 比第一个 star 值钱**。
