package dev.duragent.spring.workflow;

import dev.duragent.core.engine.Approvals;
import dev.duragent.core.engine.Budgets;
import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.RunLifecycle;
import dev.duragent.core.model.Run;
import dev.duragent.langgraph4j.DurableNodeActions;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The two demo agents. They are intentionally fake - no model is called - so the demo shows the
 * runtime, not an LLM integration.
 *
 * <p>{@code refund-agent} is the interesting one: it moves "money", so the refund node needs a
 * human approval. That is what makes the HTTP demo worth watching.
 */
@Component
public class DemoWorkflows {

    private static final Logger log = LoggerFactory.getLogger(DemoWorkflows.class);

    private final DurableSteps steps;
    private final RunLifecycle runs;
    private final Approvals approvals;
    private final Budgets budgets;

    public DemoWorkflows(DurableSteps steps, RunLifecycle runs, Approvals approvals, Budgets budgets) {
        this.steps = steps;
        this.runs = runs;
        this.approvals = approvals;
        this.budgets = budgets;
    }

    public CompiledGraph<AgentState> graphFor(Run run) throws GraphStateException {
        DurableNodeActions nodes = new DurableNodeActions(run.id(), steps, runs, approvals, budgets);
        return switch (run.workflow()) {
            case "refund-agent" -> refundGraph(run, nodes);
            case "summary-agent" -> summaryGraph(run, nodes);
            default -> throw new IllegalArgumentException("unknown workflow: " + run.workflow());
        };
    }

    private CompiledGraph<AgentState> refundGraph(Run run, DurableNodeActions nodes) throws GraphStateException {
        return new StateGraph<AgentState>(AgentState::new)
                .addNode("plan", nodes.durable("plan", AsyncNodeAction.node_async(state -> {
                    log.info("run {}: planning the refund", run.id());
                    budgets.charge(run.id(), 1_200, 3_000, 0);   // pretend a model call
                    sleep(400);
                    return Map.of("plan", "refund " + state.data().get("amount") + " CNY");
                })))
                .addNode("refund", nodes.durableWithApproval("refund",
                        "refund " + run.inputJson() + " to the customer", "refund-agent",
                        AsyncNodeAction.node_async(state -> {
                            log.info("run {}: issuing the refund", run.id());
                            sleep(600);
                            return Map.of("refunded", true);
                        })))
                .addNode("notify", nodes.durable("notify", AsyncNodeAction.node_async(state -> {
                    log.info("run {}: notifying the customer", run.id());
                    sleep(200);
                    return Map.of("notified", true);
                })))
                .addEdge(GraphDefinition.START, "plan")
                .addEdge("plan", "refund")
                .addEdge("refund", "notify")
                .addEdge("notify", GraphDefinition.END)
                .compile();
    }

    private CompiledGraph<AgentState> summaryGraph(Run run, DurableNodeActions nodes) throws GraphStateException {
        return new StateGraph<AgentState>(AgentState::new)
                .addNode("plan", nodes.durable("plan", AsyncNodeAction.node_async(state -> {
                    budgets.charge(run.id(), 900, 2_000, 0);
                    sleep(200);
                    return Map.of("outline", List.of("intro", "body"));
                })))
                .addNode("write", nodes.durable("write", AsyncNodeAction.node_async(state -> {
                    budgets.charge(run.id(), 2_400, 6_000, 0);
                    sleep(400);
                    return Map.of("summary", "durable execution explained");
                })))
                .addEdge(GraphDefinition.START, "plan")
                .addEdge("plan", "write")
                .addEdge("write", GraphDefinition.END)
                .compile();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
