package dev.duragent.core.engine;

import dev.duragent.core.model.StepRecord;
import dev.duragent.core.model.StepStatus;
import dev.duragent.core.store.jdbc.JdbcStepStore;
import dev.duragent.core.store.jdbc.SchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableStepsTest {

    private JdbcDataSource dataSource;
    private JdbcStepStore store;
    private DurableSteps steps;

    @BeforeEach
    void setUp() {
        dataSource = newDataSource();
        SchemaInitializer.initialize(dataSource);
        // Second call must be a no-op: boot paths and tests both re-run initialization.
        SchemaInitializer.initialize(dataSource);
        store = new JdbcStepStore(dataSource);
        steps = new DurableSteps(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("the body runs once and every later call replays the stored result")
    void bodyRunsOnceAndResultIsReplayed() {
        UUID runId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();

        Map<String, Object> first = steps.run(runId, "plan", new com.fasterxml.jackson.core.type.TypeReference<>() {
        }, () -> {
            executions.incrementAndGet();
            return Map.of("answer", 42);
        });
        Map<String, Object> second = steps.run(runId, "plan", new com.fasterxml.jackson.core.type.TypeReference<>() {
        }, () -> {
            executions.incrementAndGet();
            return Map.of("answer", 43);
        });

        assertEquals(1, executions.get(), "body must not run twice");
        assertEquals(42, second.get("answer"), "replay returns the stored value, not a fresh one");
        assertEquals(first, second);
    }

    @Test
    @DisplayName("a different engine instance on the same database replays instead of re-executing")
    void newEngineInstanceReplays() {
        UUID runId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();

        String first = steps.run(runId, "embed", String.class, () -> {
            executions.incrementAndGet();
            return "vector-abc";
        });

        // Simulates a process restart: brand new store + engine, same database.
        DurableSteps restarted = new DurableSteps(new JdbcStepStore(dataSource));
        String second = restarted.run(runId, "embed", String.class, () -> {
            executions.incrementAndGet();
            return "vector-xyz";
        });

        assertEquals(1, executions.get());
        assertEquals(first, second);
        StepRecord record = store.find(runId.toString(), "embed").orElseThrow();
        assertEquals(StepStatus.SUCCEEDED, record.status());
        assertEquals(1, record.attempt());
    }

    @Test
    @DisplayName("a live claim blocks a second worker instead of running the body twice")
    void liveClaimBlocksSecondWorker() {
        UUID runId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        store.claim(StepRecord.claim(runId.toString(), "charge-customer", "charge-customer",
                now, now + Duration.ofMinutes(5).toMillis()));

        AtomicInteger executions = new AtomicInteger();
        assertThrows(StepInProgressException.class,
                () -> steps.run(runId, "charge-customer", String.class, () -> {
                    executions.incrementAndGet();
                    return "charged";
                }));
        assertEquals(0, executions.get());
    }

    @Test
    @DisplayName("an expired lease is taken over and the attempt counter increments")
    void expiredLeaseIsTakenOver() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        DurableSteps leased = new DurableSteps(store, new dev.duragent.core.json.JsonCodec(),
                clock, Duration.ofSeconds(30));
        UUID runId = UUID.randomUUID();

        // A worker claimed the step and then died: the claim is still RUNNING, its lease is not.
        store.claim(StepRecord.claim(runId.toString(), "summarize", "summarize",
                clock.millis(), clock.millis() + 1_000));

        clock.advance(Duration.ofSeconds(5));

        AtomicInteger executions = new AtomicInteger();
        String result = leased.run(runId, "summarize", String.class, () -> {
            executions.incrementAndGet();
            return "summary";
        });

        assertEquals(1, executions.get());
        assertEquals("summary", result);
        StepRecord record = store.find(runId.toString(), "summarize").orElseThrow();
        assertEquals(StepStatus.SUCCEEDED, record.status());
        assertEquals(2, record.attempt(), "takeover is a new attempt");
    }

    @Test
    @DisplayName("a failure is persisted, then a retry succeeds with attempt=2")
    void failureIsPersistedAndRetried() {
        UUID runId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();

        StepExecutionException thrown = assertThrows(StepExecutionException.class,
                () -> steps.run(runId, "call-llm", String.class, () -> {
                    executions.incrementAndGet();
                    throw new IllegalStateException("upstream 529 overloaded");
                }));

        assertTrue(thrown.getCause().getMessage().contains("529"));
        StepRecord failed = store.find(runId.toString(), "call-llm").orElseThrow();
        assertEquals(StepStatus.FAILED, failed.status());
        assertTrue(failed.error().contains("529"));
        assertNotNull(failed.finishedAtMs());

        String retried = steps.run(runId, "call-llm", String.class, () -> {
            executions.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", retried);
        assertEquals(2, executions.get());
        StepRecord recovered = store.find(runId.toString(), "call-llm").orElseThrow();
        assertEquals(StepStatus.SUCCEEDED, recovered.status());
        assertEquals(2, recovered.attempt());
    }

    @Test
    @DisplayName("generic payloads survive the json round trip")
    void genericPayloadsRoundTrip() {
        UUID runId = UUID.randomUUID();
        record Plan(String goal, List<String> steps) {
        }

        Plan first = steps.run(runId, "draft-plan", Plan.class,
                () -> new Plan("ship v0.1", List.of("design", "code", "test")));
        Plan second = steps.run(runId, "draft-plan", Plan.class,
                () -> new Plan("this must never be returned", List.of()));

        assertEquals(first, second);
        assertEquals(List.of("design", "code", "test"), second.steps());
    }

    @Test
    @DisplayName("concurrent workers never execute the body twice")
    void concurrentWorkersExecuteBodyOnce() throws Exception {
        UUID runId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        steps.run(runId, "slow-tool", String.class, () -> {
                            executions.incrementAndGet();
                            sleep(400);
                            return "tool-result";
                        });
                    } catch (StepInProgressException expected) {
                        // the loser of the race
                    } catch (Throwable t) {
                        unexpected.set(t);
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }

        assertEquals(null, unexpected.get());
        assertEquals(1, executions.get(), "exactly one worker may own the side effect");
        StepRecord record = store.find(runId.toString(), "slow-tool").orElseThrow();
        assertEquals(StepStatus.SUCCEEDED, record.status());
        assertEquals(1, record.attempt());
    }

    private JdbcDataSource newDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:duragent-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Test clock that can be moved forward, so lease expiry takes milliseconds instead of minutes. */
    private static final class MutableClock extends Clock {

        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this(start, ZoneOffset.UTC);
        }

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(now, newZone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
