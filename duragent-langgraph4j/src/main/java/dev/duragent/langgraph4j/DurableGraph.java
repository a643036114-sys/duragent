package dev.duragent.langgraph4j;

import dev.duragent.core.engine.ApprovalRequiredException;
import dev.duragent.core.engine.RunLifecycle;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs a compiled graph under a durable run: finish it on success, mark it failed on a throw.
 *
 * <p>An approval pause is not a failure. When a node asks for a human, the run stays
 * {@code SUSPENDED} and the caller receives the {@link ApprovalRequiredException} itself -
 * never a wrapper - so it can show the approval id instead of a stack trace.
 */
public final class DurableGraph<S extends AgentState> {

    private static final int MAX_CAUSE_DEPTH = 10;

    private final CompiledGraph<S> graph;
    private final RunLifecycle runs;
    private final UUID runId;

    public DurableGraph(CompiledGraph<S> graph, RunLifecycle runs, UUID runId) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    public Optional<S> invoke(Map<String, Object> input) {
        try {
            Optional<S> result = graph.invoke(input);
            result.ifPresent(state -> runs.complete(runId, state.data()));
            return result;
        } catch (RuntimeException failure) {
            ApprovalRequiredException pause = findPause(failure);
            if (pause != null) {
                throw pause;
            }
            runs.fail(runId, describe(failure));
            throw failure;
        }
    }

    /** LangGraph4j wraps node exceptions in a completion wrapper; look through it. */
    private static ApprovalRequiredException findPause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && current != current.getCause() && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof ApprovalRequiredException pause) {
                return pause;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String text = root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }
}
