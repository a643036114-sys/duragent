package dev.duragent.langgraph4j;

import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.RunLifecycle;
import dev.duragent.core.engine.RunQueue;
import dev.duragent.core.engine.Worker;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.jdbc.JdbcRunLeaseStore;
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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole runtime in one test: queue a run, let a worker pick it up, execute the graph, and
 * recover when the worker that claimed it dies.
 */
class WorkerGraphTest {

    private JdbcDataSource dataSource;
    private DurableSteps steps;
    private RunLifecycle runs;
    private RunQueue queue;
    private Map<String, AtomicInteger> executions;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:worker-graph-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        steps = new DurableSteps(new JdbcStepStore(dataSource));
        runs = new RunLifecycle(new JdbcRunStore(dataSource));
        queue = new RunQueue(new JdbcRunStore(dataSource), new JdbcRunLeaseStore(dataSource));
        executions = new ConcurrentHashMap<>();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a queued run is picked up by a worker and completes")
    void queuedRunCompletesOnAWorker() throws Exception {
        Run run = runs.enqueue("two-node", Map.of());
        CompiledGraph<AgentState> graph = graph(run.id());

        try (Worker worker = new Worker("worker-a", queue,
                claimed -> new DurableGraph<>(graph, runs, claimed.id()).invoke(Map.of()),
                Duration.ofSeconds(5), Duration.ofMillis(200))) {
            assertTrue(worker.pollOnce());
        }

        assertEquals(RunStatus.COMPLETED, runs.get(run.id()).status());
        assertEquals(1, count("plan"));
        assertEquals(1, count("write"));
    }

    @Test
    @DisplayName("a run whose worker died is taken over and finishes without repeating steps")
    void deadWorkerRunIsRecovered() throws Exception {
        Run run = runs.enqueue("two-node", Map.of());

        // A worker claims the run and then vanishes: the lease is never renewed or released.
        assertTrue(queue.claim("worker-doomed", Duration.ofMillis(300)).isPresent());
        sleep(400);

        CompiledGraph<AgentState> graph = graph(run.id());
        try (Worker worker = new Worker("worker-alive", queue,
                claimed -> new DurableGraph<>(graph, runs, claimed.id()).invoke(Map.of()),
                Duration.ofSeconds(5), Duration.ofMillis(200))) {
            assertTrue(worker.pollOnce(), "the expired lease makes the run claimable again");
        }

        assertEquals(RunStatus.COMPLETED, runs.get(run.id()).status());
        assertEquals(1, count("plan"));
        assertEquals(1, count("write"));
    }

    private CompiledGraph<AgentState> graph(UUID runId) throws Exception {
        var nodes = new DurableNodeActions(runId, steps, runs);
        return new StateGraph<AgentState>(AgentState::new)
                .addNode("plan", nodes.durable("plan", AsyncNodeAction.node_async(state -> {
                    record("plan");
                    return Map.of("plan", "write a summary");
                })))
                .addNode("write", nodes.durable("write", AsyncNodeAction.node_async(state -> {
                    record("write");
                    return Map.of("written", true);
                })))
                .addEdge(GraphDefinition.START, "plan")
                .addEdge("plan", "write")
                .addEdge("write", GraphDefinition.END)
                .compile();
    }

    private int count(String node) {
        AtomicInteger counter = executions.get(node);
        return counter == null ? 0 : counter.get();
    }

    private void record(String node) {
        executions.computeIfAbsent(node, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

