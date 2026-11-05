package dev.duragent.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.duragent.core.engine.DurableSteps;
import dev.duragent.core.engine.StepExecutionException;
import dev.duragent.core.model.StepRecord;
import dev.duragent.core.store.StepStore;
import dev.duragent.core.store.jdbc.JdbcStepStore;
import dev.duragent.core.store.jdbc.SchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Three-step "agent" pipeline that crashes in the middle of step 2, then runs again.
 *
 * <p>What to watch for in the output:
 * <ul>
 *   <li>step 1 is <em>replayed</em> on the second attempt - the expensive work is not repeated;</li>
 *   <li>step 2 is <em>retried</em> - it never produced a result, so it must run again;</li>
 *   <li>step 3 runs for the first time.</li>
 * </ul>
 */
public final class CrashResumeDemo {

    private static final List<String> STEP_ORDER = List.of("load-context", "call-llm", "write-report");
    private static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();
    private static final AtomicBoolean llmHasCrashed = new AtomicBoolean(false);

    private CrashResumeDemo() {
    }

    public static void main(String[] args) throws IOException {
        Path home = Path.of("target", "duragent-demo");
        if (Files.exists(home)) {
            deleteRecursively(home);
        }
        Files.createDirectories(home);

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:file:" + toJdbcPath(home.resolve("duragent")) + ";MODE=PostgreSQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        UUID runId = UUID.randomUUID();
        System.out.println();
        System.out.println("run id: " + runId);
        System.out.println("db    : " + home.resolve("duragent.mv.db"));

        System.out.println();
        System.out.println("── attempt 1: the process is alive ──────────────────────────────");
        try {
            runPipeline(new DurableSteps(new JdbcStepStore(dataSource)), runId);
            System.out.println("unexpected: attempt 1 should have crashed");
        } catch (StepExecutionException e) {
            System.out.println("✗ crashed at step '" + e.stepKey() + "': " + e.getCause().getMessage());
        }

        System.out.println();
        System.out.println("── attempt 2: a brand new engine, same database (= process restart) ──");
        runPipeline(new DurableSteps(new JdbcStepStore(dataSource)), runId);
        System.out.println("✓ run completed");

        System.out.println();
        printRecords(new JdbcStepStore(dataSource), runId);
        printExecutions();
    }

    private static void runPipeline(DurableSteps steps, UUID runId) {
        steps.run(runId, "load-context", new TypeReference<Map<String, Object>>() {
        }, () -> {
            count("load-context");
            sleep(150);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("documents", List.of("adr-0001.md", "adr-0002.md"));
            context.put("tokens", 1_842);
            return context;
        });

        steps.run(runId, "call-llm", new TypeReference<Map<String, Object>>() {
        }, () -> {
            count("call-llm");
            sleep(300);
            if (llmHasCrashed.compareAndSet(false, true)) {
                throw new IllegalStateException("529 overloaded (simulated upstream failure)");
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("summary", "durable execution keeps the expensive step from running twice");
            answer.put("model", "deepseek-chat");
            return answer;
        });

        steps.run(runId, "write-report", new TypeReference<Map<String, Object>>() {
        }, () -> {
            count("write-report");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("path", "reports/summary.md");
            return report;
        });
    }

    private static void printRecords(StepStore store, UUID runId) {
        System.out.println();
        System.out.println("── persisted step records ───────────────────────────────────────");
        System.out.printf("%-14s %-10s %-8s %-22s %s%n", "step", "status", "attempt", "started(ms)", "error");
        for (String step : STEP_ORDER) {
            StepRecord record = store.find(runId.toString(), step).orElse(null);
            if (record == null) {
                System.out.printf("%-14s %-10s %-8s %-22s %s%n", step, "-", "-", "-", "-");
                continue;
            }
            System.out.printf("%-14s %-10s %-8d %-22d %s%n",
                    step,
                    record.status(),
                    record.attempt(),
                    record.startedAtMs(),
                    record.error() == null ? "" : record.error());
        }
    }

    private static void printExecutions() {
        System.out.println();
        System.out.println("── how many times each body actually executed ────────────────────");
        for (String step : STEP_ORDER) {
            System.out.printf("  %-14s %d%n", step, countOf(step));
        }
        System.out.println();
        System.out.println("load-context = 1 (replayed from the database on attempt 2)");
        System.out.println("call-llm     = 2 (it never produced a result, so it had to run again)");
        System.out.println("write-report = 1");
        System.out.println();
    }

    private static void count(String step) {
        EXECUTIONS.computeIfAbsent(step, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static int countOf(String step) {
        AtomicInteger counter = EXECUTIONS.get(step);
        return counter == null ? 0 : counter.get();
    }

    private static String toJdbcPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
