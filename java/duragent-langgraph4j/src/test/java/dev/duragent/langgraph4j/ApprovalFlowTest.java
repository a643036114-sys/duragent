package dev.duragent.langgraph4j;

import dev.duragent.core.engine.ApprovalRequiredException;
import dev.duragent.core.engine.Approvals;
import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.RunLifecycle;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.jdbc.JdbcApprovalStore;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalFlowTest {

    private JdbcDataSource dataSource;
    private DurableSteps steps;
    private RunLifecycle runs;
    private Approvals approvals;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:approval-flow-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        steps = new DurableSteps(new JdbcStepStore(dataSource));
        runs = new RunLifecycle(new JdbcRunStore(dataSource));
        approvals = new Approvals(new JdbcApprovalStore(dataSource), runs);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a gated node parks the run, then runs exactly once after approval")
    void graphWaitsForHumanThenFinishes() throws Exception {
        Run run = runs.start("refund-agent", Map.of("ticket", "T-9"));
        Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();

        var nodes = new DurableNodeActions(run.id(), steps, runs, approvals);

        CompiledGraph<AgentState> graph = new StateGraph<AgentState>(AgentState::new)
                .addNode("plan", nodes.durable("plan", AsyncNodeAction.node_async(state -> {
                    count(executions, "plan");
                    return Map.of("action", "refund 500 CNY");
                })))
                .addNode("refund", nodes.durableWithApproval("refund",
                        "refund 500 CNY to the customer", "refund-agent",
                        AsyncNodeAction.node_async(state -> {
                            count(executions, "refund");
                            return Map.of("refunded", true);
                        })))
                .addNode("notify", nodes.durable("notify", AsyncNodeAction.node_async(state -> {
                    count(executions, "notify");
                    return Map.of("notified", true);
                })))
                .addEdge(GraphDefinition.START, "plan")
                .addEdge("plan", "refund")
                .addEdge("refund", "notify")
                .addEdge("notify", GraphDefinition.END)
                .compile();

        var agent = new DurableGraph<>(graph, runs, run.id());

        // 1. the agent reaches the money-moving node and stops
        ApprovalRequiredException pause = assertThrows(ApprovalRequiredException.class,
                () -> agent.invoke(Map.of("ticket", "T-9")));

        assertEquals(RunStatus.SUSPENDED, runs.get(run.id()).status());
        assertEquals(1, countOf(executions, "plan"));
        assertEquals(0, countOf(executions, "refund"), "the refund must not happen before approval");
        assertEquals(0, countOf(executions, "notify"));

        // 2. a human approves, possibly days later and in another process
        approvals.approve(UUID.fromString(pause.approvalId()), "manager@corp", "ok");

        // 3. the run resumes: plan replays, refund and notify execute once
        agent.invoke(Map.of("ticket", "T-9"));

        assertEquals(RunStatus.COMPLETED, runs.get(run.id()).status());
        assertEquals(1, countOf(executions, "plan"), "plan must replay, not re-execute");
        assertEquals(1, countOf(executions, "refund"));
        assertEquals(1, countOf(executions, "notify"));
    }

    @Test
    @DisplayName("a rejected approval stops the run for good")
    void rejectionStopsTheGraph() throws Exception {
        Run run = runs.start("refund-agent", Map.of("ticket", "T-10"));
        Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();

        var nodes = new DurableNodeActions(run.id(), steps, runs, approvals);

        CompiledGraph<AgentState> graph = new StateGraph<AgentState>(AgentState::new)
                .addNode("refund", nodes.durableWithApproval("refund",
                        "refund 900 CNY", "refund-agent",
                        AsyncNodeAction.node_async(state -> {
                            count(executions, "refund");
                            return Map.of("refunded", true);
                        })))
                .addEdge(GraphDefinition.START, "refund")
                .addEdge("refund", GraphDefinition.END)
                .compile();

        var agent = new DurableGraph<>(graph, runs, run.id());
        ApprovalRequiredException pause = assertThrows(ApprovalRequiredException.class,
                () -> agent.invoke(Map.of()));

        approvals.reject(UUID.fromString(pause.approvalId()), "manager@corp", "policy violation");
        assertEquals(RunStatus.FAILED, runs.get(run.id()).status());

        assertThrows(RuntimeException.class, () -> agent.invoke(Map.of()));
        assertEquals(0, countOf(executions, "refund"), "a rejected action must never run");
    }

    private static void count(Map<String, AtomicInteger> executions, String node) {
        executions.computeIfAbsent(node, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static int countOf(Map<String, AtomicInteger> executions, String node) {
        AtomicInteger counter = executions.get(node);
        return counter == null ? 0 : counter.get();
    }
}
