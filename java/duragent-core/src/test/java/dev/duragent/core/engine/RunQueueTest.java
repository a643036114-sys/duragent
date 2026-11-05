package dev.duragent.core.engine;

import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.jdbc.JdbcRunLeaseStore;
import dev.duragent.core.store.jdbc.JdbcRunStore;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunQueueTest {

    private JdbcDataSource dataSource;
    private JdbcRunStore runStore;
    private JdbcRunLeaseStore leaseStore;
    private RunLifecycle runs;
    private MutableClock clock;
    private RunQueue queue;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:queue-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        runStore = new JdbcRunStore(dataSource);
        leaseStore = new JdbcRunLeaseStore(dataSource);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        runs = new RunLifecycle(runStore, new dev.duragent.core.json.JsonCodec(), clock);
        queue = new RunQueue(runStore, leaseStore, clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a worker claims a pending run and promotes it to RUNNING")
    void claimsPendingRun() {
        Run enqueued = runs.enqueue("ticket-agent", Map.of("ticket", "T-1"));
        assertEquals(RunStatus.PENDING, enqueued.status());

        Optional<Run> claimed = queue.claim("worker-a", Duration.ofSeconds(30));

        assertTrue(claimed.isPresent());
        assertEquals(enqueued.id(), claimed.get().id());
        assertEquals(RunStatus.RUNNING, claimed.get().status());
        assertEquals(RunStatus.RUNNING, runs.get(enqueued.id()).status());
        assertTrue(leaseStore.find(enqueued.id()).isPresent());
    }

    @Test
    @DisplayName("a live lease keeps every other worker out")
    void liveLeaseBlocksOtherWorkers() {
        runs.enqueue("ticket-agent", Map.of());

        assertTrue(queue.claim("worker-a", Duration.ofSeconds(30)).isPresent());
        assertTrue(queue.claim("worker-b", Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    @DisplayName("an expired lease is taken over by another worker")
    void expiredLeaseIsTakenOver() {
        Run run = runs.enqueue("ticket-agent", Map.of());
        assertTrue(queue.claim("worker-a", Duration.ofSeconds(10)).isPresent());

        clock.advance(Duration.ofSeconds(11));

        Optional<Run> takenOver = queue.claim("worker-b", Duration.ofSeconds(10));
        assertTrue(takenOver.isPresent());
        assertEquals(run.id(), takenOver.get().id());
        assertEquals("worker-b", leaseStore.find(run.id()).orElseThrow().workerId());
    }

    @Test
    @DisplayName("a heartbeat extends the lease")
    void heartbeatExtendsTheLease() {
        Run run = runs.enqueue("ticket-agent", Map.of());
        queue.claim("worker-a", Duration.ofSeconds(10));

        clock.advance(Duration.ofSeconds(8));
        assertTrue(queue.heartbeat("worker-a", run.id(), Duration.ofSeconds(10)));

        clock.advance(Duration.ofSeconds(8));
        assertTrue(queue.claim("worker-b", Duration.ofSeconds(10)).isEmpty(),
                "the renewed lease is still alive 16s after the claim");
    }

    @Test
    @DisplayName("a suspended run is not claimable: it is waiting for a human, not a worker")
    void suspendedRunIsNotClaimable() {
        Run run = runs.enqueue("refund-agent", Map.of());
        runs.resume(run.id());
        runs.suspend(run.id(), "approval required");

        assertTrue(queue.claim("worker-a", Duration.ofSeconds(10)).isEmpty());
    }

    @Test
    @DisplayName("releasing a run hands it straight to the next worker")
    void releaseMakesTheRunClaimableAgain() {
        Run run = runs.enqueue("ticket-agent", Map.of());
        queue.claim("worker-a", Duration.ofSeconds(60));

        assertTrue(queue.release("worker-a", run.id()));

        assertTrue(queue.claim("worker-b", Duration.ofSeconds(60)).isPresent());
        assertFalse(leaseStore.find(run.id()).map(l -> !l.workerId().equals("worker-b")).orElse(false));
    }

    /** Test clock that can be moved forward. */
    private static final class MutableClock extends Clock {

        private Instant now;
        private final ZoneId zone = ZoneOffset.UTC;

        MutableClock(Instant start) {
            this.now = start;
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
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
