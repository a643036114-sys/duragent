package dev.duragent.langgraph4j;

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
 * <p>Invoking an already finished run is harmless: every node replays from the journal, so the
 * call is a read, not a re-execution.
 */
public final class DurableGraph<S extends AgentState> {

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
            runs.fail(runId, describe(failure));
            throw failure;
        }
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
