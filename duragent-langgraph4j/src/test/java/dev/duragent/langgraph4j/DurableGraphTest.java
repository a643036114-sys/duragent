package dev.duragent.langgraph4j;

import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.RunLifecycle;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.jdbc.JdbcRunStore;
import dev.duragent.core.store.jdbc.JdbcStepStore;
import dev.duragent.core.store.jdbc.SchemaInitializer;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableGraphTest {

    private JdbcDataSource dataSource;
    private DurableSteps steps;
    private RunLifecycle runs;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:graph-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        steps = new DurableSteps(new JdbcStepStore(dataSource));
        runs = new RunLifecycle(new JdbcRunStore(dataSource));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a node that crashed re-runs, the nodes before it replay, the nodes after it run once")
    void crashThenResume() throws Exception {
        Run run = runs.start("summary-agent", Map.of("topic", "durable execution"));
        UUID runId = run.id();

        Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
        AtomicBoolean crashOnce = new AtomicBoolean(true);

        var nodes = new DurableNodeActions(runId, steps, runs);

        CompiledGraph<AgentState> graph = new StateGraph<AgentState>(AgentState::new)
                .addNode("plan", nodes.durable("plan", AsyncNodeAction.node_async(state -> {
                    count(executions, "plan");
                    return Map.of("outline", List.of("intro", "body"));
                })))
                .addNode("summarize", nodes.durable("summarize", AsyncNodeAction.node_async(state -> {
                    count(executions, "summarize");
                    if (crashOnce.compareAndSet(true, false)) {
                        throw new IllegalStateException("529 overloaded (simulated)");
                    }
                    return Map.of("summary", "durable execution explained");
                })))
                .addNode("publish", nodes.durable("publish", AsyncNodeAction.node_async(state -> {
                    count(executions, "publish");
                    return Map.of("published", true);
                })))
                .addEdge(GraphDefinition.START, "plan")
                .addEdge("plan", "summarize")
                .addEdge("summarize", "publish")
                .addEdge("publish", GraphDefinition.END)
                .compile();

        var agent = new DurableGraph<>(graph, runs, runId);

        // attempt 1: dies inside "summarize"
        assertThrows(RuntimeException.class, () -> agent.invoke(Map.of("topic", "durable execution")));
        assertEquals(RunStatus.FAILED, runs.get(runId).status());
        assertEquals(1, countOf(executions, "plan"));
        assertEquals(1, countOf(executions, "summarize"));
        assertEquals(0, countOf(executions, "publish"));

        // attempt 2: a restart, from the same database
        runs.resume(runId);
        Optional<AgentState> result = agent.invoke(Map.of("topic", "durable execution"));

        assertTrue(result.isPresent());
        assertEquals("durable execution explained", result.orElseThrow().data().get("summary"));
        assertEquals(true, result.orElseThrow().data().get("published"));

        assertEquals(1, countOf(executions, "plan"), "plan must replay, not re-execute");
        assertEquals(2, countOf(executions, "summarize"), "the crashed node must retry");
        assertEquals(1, countOf(executions, "publish"));
        assertEquals(RunStatus.COMPLETED, runs.get(runId).status());
    }

    @Test
    @DisplayName("checkpoints form a timeline of the nodes that finished")
    void checkpointsRecordNodeTimeline() throws Exception {
        Run run = runs.start("two-node", Map.of());
        UUID runId = run.id();

        Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
        var nodes = new DurableNodeActions(runId, steps, runs);

        CompiledGraph<AgentState> graph = new StateGraph<AgentState>(AgentState::new)
                .addNode("first", nodes.durable("first", AsyncNodeAction.node_async(state -> {
                    count(executions, "first");
                    return Map.of("a", 1);
                })))
                .addNode("second", nodes.durable("second", AsyncNodeAction.node_async(state -> {
                    count(executions, "second");
                    return Map.of("b", 2);
                })))
                .addEdge(GraphDefinition.START, "first")
                .addEdge("first", "second")
                .addEdge("second", GraphDefinition.END)
                .compile();

        new DurableGraph<>(graph, runs, runId).invoke(Map.of());

        var timeline = runs.timeline(runId);
        assertEquals(List.of("first", "second"), timeline.stream().map(c -> c.node()).toList());
        assertEquals(List.of(1, 2), timeline.stream().map(c -> c.seq()).toList());
        assertTrue(timeline.get(1).stateJson().contains("\"b\":2"));
    }

    @Test
    @DisplayName("invoking a completed run re-executes nothing")
    void replayAfterCompletionIsFree() throws Exception {
        Run run = runs.start("idempotent", Map.of());
        UUID runId = run.id();

        Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
        var nodes = new DurableNodeActions(runId, steps, runs);

        CompiledGraph<AgentState> graph = new StateGraph<AgentState>(AgentState::new)
                .addNode("only", nodes.durable("only", AsyncNodeAction.node_async(state -> {
                    count(executions, "only");
                    return Map.of("done", true);
                })))
                .addEdge(GraphDefinition.START, "only")
                .addEdge("only", GraphDefinition.END)
                .compile();

        var agent = new DurableGraph<>(graph, runs, runId);
        agent.invoke(Map.of());
        agent.invoke(Map.of());
        agent.invoke(Map.of());

        assertEquals(1, countOf(executions, "only"));
    }

    private static void count(Map<String, AtomicInteger> executions, String node) {
        executions.computeIfAbsent(node, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static int countOf(Map<String, AtomicInteger> executions, String node) {
        AtomicInteger counter = executions.get(node);
        return counter == null ? 0 : counter.get();
    }
}
