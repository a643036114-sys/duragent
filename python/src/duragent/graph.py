"""Wrapping LangGraph nodes so they become durable steps.

LangGraph 的节点就是一个普通函数：``state -> dict``。正因为它是普通函数，
我们才能在外面包一层——包完之后，这个节点就具备了"跑过就不再跑"的能力，
而图本身完全不用改。
"""

from __future__ import annotations

import functools
import time
import uuid
from typing import Any, Callable

from duragent.steps import DEFAULT_LEASE_SECONDS, StepInProgress, run_step

__all__ = ["Journal", "new_run_id", "StepInProgress"]


def new_run_id() -> str:
    """生成一个 run id。一次完整执行用同一个 id，这样重启后才能接上。"""
    return str(uuid.uuid4())


class Journal:
    """一次 run 的日志本：把节点包成 durable step。

    用法一，直接包：

        journal = Journal(conn, run_id)
        graph.add_node("plan", journal.durable("plan", plan_fn))

    用法二，当装饰器：

        @journal.durable("plan")
        def plan(state):
            return {"outline": [...]}
    """

    def __init__(
        self,
        conn,
        run_id: str,
        *,
        lease: float = DEFAULT_LEASE_SECONDS,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.conn = conn
        self.run_id = run_id
        self.lease = lease
        self.clock = clock

    def durable(self, step_name: str, fn: Callable[[Any], Any] | None = None):
        """把 ``fn`` 包成一个"最多执行一次"的节点。

        ``step_name`` 必须在这个 run 内唯一——它就是幂等键。循环里同一个节点跑多次时，
        要么用 ``charge#1`` / ``charge#2`` 区分，要么等后面加自动计数。
        """

        def wrap(fn: Callable[[Any], Any]) -> Callable[[Any], Any]:
            @functools.wraps(fn)
            def node(state):
                # 注意：这里传的是 lambda，不是 fn(state) 的结果。
                # run_step 只有在"确实该执行"时才会调用它，回放时根本不会碰 fn。
                return run_step(
                    self.conn,
                    self.run_id,
                    step_name,
                    lambda: fn(state),
                    lease=self.lease,
                    clock=self.clock,
                )

            node.__duragent_step__ = step_name
            return node

        return wrap if fn is None else wrap(fn)
