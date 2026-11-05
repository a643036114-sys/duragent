package dev.duragent.core.engine;

import dev.duragent.core.model.Budget;
import dev.duragent.core.model.BudgetEntry;
import dev.duragent.core.store.jdbc.JdbcBudgetStore;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetsTest {

    private JdbcDataSource dataSource;
    private JdbcBudgetStore store;
    private Budgets budgets;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:budget-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);

        store = new JdbcBudgetStore(dataSource);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        budgets = new Budgets(store, clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("charges accumulate into one ledger")
    void chargesAccumulate() {
        UUID runId = UUID.randomUUID();
        budgets.set(runId, Budget.unlimited().withMaxCostMicros(1_000_000));

        budgets.charge(runId, 100, 1_000, 1);
        budgets.charge(runId, 200, 2_000, 1);

        BudgetEntry entry = budgets.usage(runId).orElseThrow();
        assertEquals(300, entry.usedTokens());
        assertEquals(3_000, entry.usedCostMicros());
        assertEquals(2, entry.usedSteps());
    }

    @Test
    @DisplayName("the last cent can be spent, the next one is refused and not recorded")
    void stopsAtExactLimit() {
        UUID runId = UUID.randomUUID();
        budgets.set(runId, Budget.unlimited().withMaxCostMicros(100));

        for (int i = 0; i < 4; i++) {
            budgets.charge(runId, 0, 25, 0);
        }

        BudgetExceededException exceeded = assertThrows(BudgetExceededException.class,
                () -> budgets.charge(runId, 0, 25, 0));

        assertEquals("cost", exceeded.limit());
        assertEquals(100, exceeded.allowed());
        assertEquals(125, exceeded.attempted());
        assertEquals(100, budgets.usage(runId).orElseThrow().usedCostMicros(),
                "a refused charge must not be billed");
    }

    @Test
    @DisplayName("tokens and steps have their own limits")
    void tokenAndStepLimits() {
        UUID runId = UUID.randomUUID();
        budgets.set(runId, Budget.unlimited().withMaxTokens(1_000).withMaxSteps(2));

        budgets.charge(runId, 600, 0, 1);
        assertEquals("tokens", assertThrows(BudgetExceededException.class,
                () -> budgets.charge(runId, 500, 0, 0)).limit());

        budgets.charge(runId, 0, 0, 1);
        assertEquals("steps", assertThrows(BudgetExceededException.class,
                () -> budgets.charge(runId, 0, 0, 1)).limit());
    }

    @Test
    @DisplayName("a run without a budget is unlimited")
    void noBudgetMeansUnlimited() {
        UUID runId = UUID.randomUUID();
        assertTrue(budgets.charge(runId, Long.MAX_VALUE, Long.MAX_VALUE, 1_000_000).isEmpty());
        assertTrue(budgets.usage(runId).isEmpty());
    }

    @Test
    @DisplayName("two workers cannot spend the same money twice")
    void concurrentChargesCannotOverspend() throws Exception {
        UUID runId = UUID.randomUUID();
        budgets.set(runId, Budget.unlimited().withMaxCostMicros(100));

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        budgets.charge(runId, 0, 60, 0);
                        accepted.incrementAndGet();
                    } catch (BudgetExceededException expected) {
                        refused.incrementAndGet();
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
        assertEquals(1, accepted.get());
        assertEquals(1, refused.get());
        assertEquals(60, budgets.usage(runId).orElseThrow().usedCostMicros());
    }

    @Test
    @DisplayName("wall clock is checked, not charged")
    void wallClockLimit() {
        UUID runId = UUID.randomUUID();
        budgets.set(runId, Budget.unlimited().withMaxWallClock(Duration.ofSeconds(1)));

        budgets.checkWallClock(runId);

        clock.advance(Duration.ofSeconds(2));
        BudgetExceededException exceeded = assertThrows(BudgetExceededException.class,
                () -> budgets.checkWallClock(runId));
        assertEquals("wall-clock", exceeded.limit());
    }

    @Test
    @DisplayName("raising the budget keeps what was already spent")
    void raiseKeepsSpend() {
        UUID runId = UUID.randomUUID();
        budgets.set(runId, Budget.unlimited().withMaxCostMicros(100));
        budgets.charge(runId, 0, 40, 0);

        budgets.raise(runId, Budget.unlimited().withMaxCostMicros(200));
        budgets.charge(runId, 0, 100, 0);

        assertEquals(140, budgets.usage(runId).orElseThrow().usedCostMicros());
        assertEquals(200, budgets.usage(runId).orElseThrow().budget().maxCostMicros());
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
