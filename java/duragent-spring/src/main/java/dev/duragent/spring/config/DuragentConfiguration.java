package dev.duragent.spring.config;

import dev.duragent.core.engine.Approvals;
import dev.duragent.core.engine.Budgets;
import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.RunExecutor;
import dev.duragent.core.engine.RunLifecycle;
import dev.duragent.core.engine.RunQueue;
import dev.duragent.core.engine.Worker;
import dev.duragent.core.json.JsonCodec;
import dev.duragent.core.store.ApprovalStore;
import dev.duragent.core.store.BudgetStore;
import dev.duragent.core.store.RunLeaseStore;
import dev.duragent.core.store.RunStore;
import dev.duragent.core.store.StepStore;
import dev.duragent.core.store.jdbc.JdbcApprovalStore;
import dev.duragent.core.store.jdbc.JdbcBudgetStore;
import dev.duragent.core.store.jdbc.JdbcRunLeaseStore;
import dev.duragent.core.store.jdbc.JdbcRunStore;
import dev.duragent.core.store.jdbc.JdbcStepStore;
import dev.duragent.core.store.jdbc.SchemaInitializer;
import dev.duragent.langgraph4j.DurableGraph;
import dev.duragent.spring.workflow.DemoWorkflows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Wires the runtime by hand. Deliberately no auto-configuration yet: a reader should be able to
 * trace one bean to one class in {@code duragent-core}.
 */
@Configuration
public class DuragentConfiguration {

    @Bean
    public JsonCodec jsonCodec() {
        return new JsonCodec();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ApplicationRunner schemaInitializer(DataSource dataSource) {
        return args -> SchemaInitializer.initialize(dataSource);
    }

    @Bean
    public StepStore stepStore(DataSource dataSource) {
        return new JdbcStepStore(dataSource);
    }

    @Bean
    public RunStore runStore(DataSource dataSource) {
        return new JdbcRunStore(dataSource);
    }

    @Bean
    public RunLeaseStore runLeaseStore(DataSource dataSource) {
        return new JdbcRunLeaseStore(dataSource);
    }

    @Bean
    public ApprovalStore approvalStore(DataSource dataSource) {
        return new JdbcApprovalStore(dataSource);
    }

    @Bean
    public BudgetStore budgetStore(DataSource dataSource) {
        return new JdbcBudgetStore(dataSource);
    }

    @Bean
    public DurableSteps durableSteps(StepStore stepStore, JsonCodec json, Clock clock) {
        return new DurableSteps(stepStore, json, clock, Duration.ofMinutes(2));
    }

    @Bean
    public RunLifecycle runLifecycle(RunStore runStore, JsonCodec json, Clock clock) {
        return new RunLifecycle(runStore, json, clock);
    }

    @Bean
    public Approvals approvals(ApprovalStore approvalStore, RunLifecycle runs, Clock clock) {
        return new Approvals(approvalStore, runs, clock);
    }

    @Bean
    public Budgets budgets(BudgetStore budgetStore, Clock clock) {
        return new Budgets(budgetStore, clock);
    }

    @Bean
    public RunQueue runQueue(RunStore runStore, RunLeaseStore leaseStore, Clock clock) {
        return new RunQueue(runStore, leaseStore, clock);
    }

    @Bean(destroyMethod = "close")
    public Worker worker(RunQueue queue, DemoWorkflows workflows, RunLifecycle runs, JsonCodec json,
                         @Value("${duragent.worker.id:worker-local}") String workerId,
                         @Value("${duragent.worker.lease:30s}") Duration lease,
                         @Value("${duragent.worker.heartbeat:5s}") Duration heartbeat) {
        RunExecutor executor = run -> {
            Map<String, Object> input = json.fromJson(run.inputJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            new DurableGraph<>(workflows.graphFor(run), runs, run.id()).invoke(input);
        };
        return new Worker(workerId, queue, executor, lease, heartbeat);
    }
}
