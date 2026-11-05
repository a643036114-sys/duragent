package dev.duragent.langgraph4j;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * Turns a LangGraph4j node into a durable step.
 *
 * <pre>{@code
 * var nodes = new DurableNodeActions(runId, steps, runs);
 *
 * var graph = new StateGraph<AgentState>(AgentState::new)
 *         .addNode("plan",      nodes.durable("plan",      planAction))
 *         .addNode("summarize", nodes.durable("summarize", summarizeAction))
 *         .addEdge(START, "plan")
 *         .addEdge("plan", "summarize")
 *         .addEdge("summarize", END)
 *         .compile();
 * }</pre>
 *
 * <h2>Why wrapping the action is enough</h2>
 * A resumed graph replays its nodes from the start. Because every node's output is journaled,
 * the replays are free: no model call, no tool call, no charge. The state rebuilds itself, and
 * only the first node that never finished actually executes. Conditional edges keep working
 * because they read replayed state, which is identical to what they read the first time.
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

    /** Durable steps only, no run checkpoints. */
    public DurableNodeActions(UUID runId, DurableSteps steps) {
        this(runId, steps, null);
    }

    /** Durable steps plus a checkpoint after every node, so the run has a timeline. */
    public DurableNodeActions(UUID runId, DurableSteps steps, RunLifecycle runs) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.runs = runs;
    }

    /**
     * @param nodeName stable node name; it doubles as the step idempotency key, so renaming a
     *                 node re-executes it
     */
    public <S extends AgentState> AsyncNodeAction<S> durable(String nodeName, AsyncNodeAction<S> action) {
        Objects.requireNonNull(action, "action");
        return state -> CompletableFuture.supplyAsync(() -> {
            Map<String, Object> delta = steps.run(runId, nodeName, DELTA,
                    () -> action.apply(state).join());

            if (runs != null) {
                Map<String, Object> merged = new LinkedHashMap<>(state.data());
                if (delta != null) {
                    merged.putAll(delta);
                }
                runs.checkpoint(runId, nodeName, merged);
            }
            return delta;
        });
    }
}
