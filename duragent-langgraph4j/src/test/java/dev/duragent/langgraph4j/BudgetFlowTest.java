package dev.duragent.langgraph4j;

import dev.duragent.core.engine.BudgetExceededException;
import dev.duragent.core.engine.Budgets;
import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.RunLifecycle;
import dev.duragent.core.model.Budget;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.jdbc.JdbcBudgetStore;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetFlowTest {

    private JdbcDataSource dataSource;
    private JdbcStepStore stepStore;
    private DurableSteps steps;
    private RunLifecycle runs;
    private Budgets budgets;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:budget-flow-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        stepStore = new JdbcStepStore(dataSource);
        steps = new DurableSteps(stepStore);
        runs = new RunLifecycle(new JdbcRunStore(dataSource));
        budgets = new Budgets(new JdbcBudgetStore(dataSource));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a node that keeps spending is stopped at the cap, without a poisoned journal")
    void burningNodeStopsAtTheCap() throws Exception {
        Run run = runs.start("cost-agent", Map.of());
        UUID runId = run.id();
        budgets.set(runId, Budget.unlimited().withMaxCostMicros(80_000));   // 0.08

        var nodes = new DurableNodeActions(runId, steps, runs, null, budgets);
        CompiledGraph<AgentState> graph = burningGraph(runId, nodes, 5);
        var agent = new DurableGraph<>(graph, runs, runId);

        BudgetExceededException exceeded = assertThrows(BudgetExceededException.class,
                () -> agent.invoke(Map.of()));

        assertEquals("cost", exceeded.limit());
        assertEquals(RunStatus.CANCELED, runs.get(runId).status());
        assertEquals(80_000, budgets.usage(runId).orElseThrow().usedCostMicros(),
                "spend stops exactly at the cap, never above it");

        // The step was released, not marked FAILED: it produced no result and must be retryable
        // after a human raises the budget.
        assertTrue(stepStore.find(runId.toString(), "burn").isEmpty(),
                "an aborted step must leave no journal row");
    }

    @Test
    @DisplayName("raising the budget and resuming re-executes the aborted step and finishes")
    void raiseBudgetThenResume() throws Exception {
        Run run = runs.start("cost-agent", Map.of());
        UUID runId = run.id();
        budgets.set(runId, Budget.unlimited().withMaxCostMicros(80_000));

        var nodes = new DurableNodeActions(runId, steps, runs, null, budgets);
        CompiledGraph<AgentState> graph = burningGraph(runId, nodes, 5);
        var agent = new DurableGraph<>(graph, runs, runId);

        assertThrows(BudgetExceededException.class, () -> agent.invoke(Map.of()));
        assertTrue(stepStore.find(runId.toString(), "burn").isEmpty());

        budgets.raise(runId, Budget.unlimited().withMaxCostMicros(200_000));
        runs.resume(runId);
        agent.invoke(Map.of());

        assertEquals(RunStatus.COMPLETED, runs.get(runId).status());
        assertEquals(180_000, budgets.usage(runId).orElseThrow().usedCostMicros(),
                "80k from the aborted attempt plus 100k from the retry");
    }

    @Test
    @DisplayName("replaying a finished node does not spend anything")
    void replayIsFree() throws Exception {
        Run run = runs.start("cost-agent", Map.of());
        UUID runId = run.id();
        budgets.set(runId, Budget.unlimited().withMaxCostMicros(1_000_000));

        var nodes = new DurableNodeActions(runId, steps, runs, null, budgets);
        CompiledGraph<AgentState> graph = burningGraph(runId, nodes, 1);
        var agent = new DurableGraph<>(graph, runs, runId);

        agent.invoke(Map.of());
        long afterFirst = budgets.usage(runId).orElseThrow().usedCostMicros();

        agent.invoke(Map.of());
        agent.invoke(Map.of());

        assertEquals(afterFirst, budgets.usage(runId).orElseThrow().usedCostMicros(),
                "replays must not charge the budget again");
    }

    /** A single node that spends 20_000 micro-units {@code times} times. */
    private CompiledGraph<AgentState> burningGraph(UUID runId, DurableNodeActions nodes, int times)
            throws Exception {
        AtomicInteger unused = new AtomicInteger();
        return new StateGraph<AgentState>(AgentState::new)
                .addNode("burn", nodes.durable("burn", AsyncNodeAction.node_async(state -> {
                    unused.incrementAndGet();
                    for (int i = 0; i < times; i++) {
                        budgets.charge(runId, 1_000, 20_000, 0);
                    }
                    return Map.of("spentRounds", times);
                })))
                .addEdge(GraphDefinition.START, "burn")
                .addEdge("burn", GraphDefinition.END)
                .compile();
    }
}
