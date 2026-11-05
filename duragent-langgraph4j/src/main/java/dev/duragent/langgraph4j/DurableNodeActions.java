package dev.duragent.langgraph4j;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.duragent.core.engine.Approvals;
import dev.duragent.core.engine.Budgets;
import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.RunLifecycle;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a LangGraph4j node into a durable step, optionally gated by an approval and metered
 * against the run budget.
 *
 * <pre>{@code
 * var nodes = new DurableNodeActions(runId, steps, runs, approvals, budgets);
 *
 * var graph = new StateGraph<AgentState>(AgentState::new)
 *         .addNode("plan",   nodes.durable("plan", planAction))
 *         .addNode("refund", nodes.durableWithApproval("refund",
 *                                   "refund 500 CNY to customer", "ticket-agent", refundAction))
 *         .addEdge(START, "plan")
 *         .addEdge("plan", "refund")
 *         .addEdge("refund", END)
 *         .compile();
 * }</pre>
 *
 * <h2>Why wrapping the action is enough</h2>
 * A resumed graph replays its nodes from the start. Because every node's output is journaled,
 * the replays are free: no model call, no tool call, no charge. The state rebuilds itself, and
 * only the first node that never finished actually executes. Conditional edges keep working
 * because they read replayed state, which is identical to what they read the first time.
 *
 * <h2>Metering</h2>
 * Every executed node spends one step from the run budget; a replay spends nothing. Token and
 * money charges are the node's business, because only the node knows what a model call cost:
 * {@code budgets.charge(runId, answer.tokens(), answer.costMicros(), 0)}.
 *
 * <h2>What this assumes</h2>
 * Node actions must be deterministic given the same input state (that is true of any graph you
 * want to resume), and channels must be plain "replace" channels. Graphs using reducer /
 * appender channels get a checkpoint state that is a shallow merge of input and delta; proper
 * channel replay is tracked in the roadmap.
 */
public final class DurableNodeActions {

    private static final TypeReference<Map<String, Object>> DELTA = new TypeReference<>() {
    };

    private final UUID runId;
    private final DurableSteps steps;
    private final RunLifecycle runs;
    private final Approvals approvals;
    private final Budgets budgets;

    /** Durable steps only, no run checkpoints. */
    public DurableNodeActions(UUID runId, DurableSteps steps) {
        this(runId, steps, null, null, null);
    }

    /** Durable steps plus a checkpoint after every node, so the run has a timeline. */
    public DurableNodeActions(UUID runId, DurableSteps steps, RunLifecycle runs) {
        this(runId, steps, runs, null, null);
    }

    /** Adds approval gates. */
    public DurableNodeActions(UUID runId, DurableSteps steps, RunLifecycle runs, Approvals approvals) {
        this(runId, steps, runs, approvals, null);
    }

    /** Full setup: durable steps, checkpoints, approvals and budget metering. */
    public DurableNodeActions(UUID runId, DurableSteps steps, RunLifecycle runs,
                              Approvals approvals, Budgets budgets) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.runs = runs;
        this.approvals = approvals;
        this.budgets = budgets;
    }

    /**
     * @param nodeName stable node name; it doubles as the step idempotency key, so renaming a
     *                 node re-executes it
     */
    public <S extends AgentState> AsyncNodeAction<S> durable(String nodeName, AsyncNodeAction<S> action) {
        Objects.requireNonNull(action, "action");
        return state -> CompletableFuture.supplyAsync(() -> runStep(nodeName, state, action));
    }

    /**
     * Same as {@link #durable}, but the node only runs once a human approved it. The first call
     * parks the run and throws {@code ApprovalRequiredException}; after {@code approve} the next
     * invoke replays everything before this node and executes it for real.
     */
    public <S extends AgentState> AsyncNodeAction<S> durableWithApproval(
            String nodeName, String reason, String requestedBy, AsyncNodeAction<S> action) {
        Objects.requireNonNull(action, "action");
        if (approvals == null) {
            throw new IllegalStateException("durableWithApproval needs an Approvals instance");
        }
        return state -> CompletableFuture.supplyAsync(() -> {
            // Checked before the step claim so an approval pause is never journaled as a failed
            // step. On replay the stored APPROVED row makes this a no-op.
            approvals.requireApproval(runId, nodeName, reason, requestedBy);
            return runStep(nodeName, state, action);
        });
    }

    private <S extends AgentState> Map<String, Object> runStep(
            String nodeName, S state, AsyncNodeAction<S> action) {
        Map<String, Object> delta = steps.run(runId, nodeName, DELTA, () -> {
            if (budgets != null) {
                budgets.checkWallClock(runId);
            }
            Map<String, Object> output = action.apply(state).join();
            if (budgets != null) {
                // One step per executed node. A replay never reaches this line.
                budgets.charge(runId, 0, 0, 1);
            }
            return output;
        });

        if (runs != null) {
            Map<String, Object> merged = new LinkedHashMap<>(state.data());
            if (delta != null) {
                merged.putAll(delta);
            }
            runs.checkpoint(runId, nodeName, merged);
        }
        return delta;
    }
}
