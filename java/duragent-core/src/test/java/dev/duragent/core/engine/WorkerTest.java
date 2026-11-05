package dev.duragent.core.engine;

import dev.duragent.core.model.Run;
import dev.duragent.core.store.jdbc.JdbcRunLeaseStore;
import dev.duragent.core.store.jdbc.JdbcRunStore;
import dev.duragent.core.store.jdbc.SchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerTest {

    private JdbcDataSource dataSource;
    private JdbcRunLeaseStore leaseStore;
    private RunLifecycle runs;
    private RunQueue queue;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:worker-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        JdbcRunStore runStore = new JdbcRunStore(dataSource);
        leaseStore = new JdbcRunLeaseStore(dataSource);
        runs = new RunLifecycle(runStore);
        queue = new RunQueue(runStore, leaseStore);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a worker claims, executes and releases the run")
    void executesAndReleases() {
        Run run = runs.enqueue("ticket-agent", Map.of("ticket", "T-1"));
        AtomicReference<UUID> executed = new AtomicReference<>();

        try (Worker worker = new Worker("worker-a", queue,
                claimed -> executed.set(claimed.id()), Duration.ofSeconds(10), Duration.ofMillis(200))) {
            assertTrue(worker.pollOnce());
        }

        assertEquals(run.id(), executed.get());
        assertTrue(leaseStore.find(run.id()).isEmpty(), "the lease is released when the worker is done");
    }

    @Test
    @DisplayName("an empty queue is not an error")
    void emptyQueueIsQuiet() {
        try (Worker worker = new Worker("worker-a", queue, claimed -> {
        }, Duration.ofSeconds(10), Duration.ofMillis(200))) {
            assertFalse(worker.pollOnce());
        }
    }

    @Test
    @DisplayName("the heartbeat keeps a long run from being stolen")
    void heartbeatProtectsALongRun() throws Exception {
        runs.enqueue("slow-agent", Map.of());
        CountDownLatch started = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Worker worker = new Worker("worker-a", queue, claimed -> {
            started.countDown();
            Thread.sleep(1_500);
        }, Duration.ofSeconds(1), Duration.ofMillis(200))) {

            Future<Boolean> polling = pool.submit(worker::pollOnce);
            assertTrue(started.await(2, TimeUnit.SECONDS), "the worker should start executing");

            // 1.2s in, the original 1s lease would be dead without heartbeats.
            Thread.sleep(1_200);
            assertTrue(queue.claim("worker-b", Duration.ofSeconds(1)).isEmpty(),
                    "a heartbeating run must not be stolen");

            assertTrue(polling.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a worker that dies mid-run leaves the run reclaimable after the lease expires")
    void deadWorkerIsRecoveredByLeaseExpiry() {
        Run run = runs.enqueue("slow-agent", Map.of());

        // A worker claims the run and then vanishes: no release, no heartbeat.
        queue.claim("worker-doomed", Duration.ofMillis(300));

        assertTrue(queue.claim("worker-b", Duration.ofMillis(100)).isEmpty(), "lease is still fresh");

        sleep(400);

        assertTrue(queue.claim("worker-b", Duration.ofMillis(1_000)).isPresent(),
                "after the lease expires another worker takes the run");
        assertEquals("worker-b", leaseStore.find(run.id()).orElseThrow().workerId());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
