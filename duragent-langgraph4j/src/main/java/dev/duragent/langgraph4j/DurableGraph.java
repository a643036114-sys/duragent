package dev.duragent.langgraph4j;

import dev.duragent.core.engine.ApprovalRequiredException;
import dev.duragent.core.engine.BudgetExceededException;
import dev.duragent.core.engine.RunLifecycle;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs a compiled graph under a durable run: finish it on success, cancel it on a guardrail,
 * fail it otherwise.
 *
 * <p>Two exceptions are control flow rather than failure. An approval pause leaves the run
 * {@code SUSPENDED}; a budget stop leaves it {@code CANCELED} with its state intact. Both are
 * rethrown unwrapped so the caller sees the real type instead of a completion wrapper.
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
            ApprovalRequiredException pause = findCause(failure, ApprovalRequiredException.class);
            if (pause != null) {
                throw pause;
            }

            BudgetExceededException budget = findCause(failure, BudgetExceededException.class);
            if (budget != null) {
                runs.cancel(runId, budget.getMessage());
                throw budget;
            }

            runs.fail(runId, describe(failure));
            throw failure;
        }
    }

    /** LangGraph4j wraps node exceptions in a completion wrapper; look through it. */
    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && current != current.getCause() && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
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
