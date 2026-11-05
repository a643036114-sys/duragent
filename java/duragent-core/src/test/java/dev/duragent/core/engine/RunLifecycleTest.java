package dev.duragent.core.engine;

import dev.duragent.core.model.Checkpoint;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.jdbc.JdbcRunStore;
import dev.duragent.core.store.jdbc.SchemaInitializer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLifecycleTest {

    private JdbcDataSource dataSource;
    private JdbcRunStore store;
    private RunLifecycle runs;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:runs-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaInitializer.initialize(dataSource);
        store = new JdbcRunStore(dataSource);
        runs = new RunLifecycle(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a started run is RUNNING at version 0")
    void startCreatesRunningRun() {
        Run run = runs.start("ticket-agent", Map.of("ticketId", "T-1"));

        assertEquals(RunStatus.RUNNING, run.status());
        assertEquals(0, run.version());
        assertEquals(1, run.attempt());
        assertTrue(run.inputJson().contains("T-1"));
        assertTrue(store.find(run.id()).isPresent());
    }

    @Test
    @DisplayName("checkpoints get increasing seqs and move the cursor")
    void checkpointsAdvanceTheCursor() {
        Run run = runs.start("ticket-agent", Map.of("ticketId", "T-1"));

        Run afterFirst = runs.checkpoint(run.id(), "plan", Map.of("step", "plan", "tokens", 120));
        assertEquals("plan", afterFirst.cursor());
        assertEquals(1, afterFirst.version());

        Run afterSecond = runs.checkpoint(run.id(), "retrieve", Map.of("step", "retrieve", "docs", 3));
        assertEquals("retrieve", afterSecond.cursor());
        assertEquals(2, afterSecond.version());

        List<Checkpoint> timeline = runs.timeline(run.id());
        assertEquals(List.of(1, 2), timeline.stream().map(Checkpoint::seq).toList());
        assertEquals(List.of("plan", "retrieve"), timeline.stream().map(Checkpoint::node).toList());

        Checkpoint latest = runs.resumePoint(run.id()).orElseThrow();
        assertEquals(2, latest.seq());
        assertEquals("retrieve", latest.node());
        assertTrue(latest.stateJson().contains("\"docs\":3"));
    }

    @Test
    @DisplayName("complete and fail are terminal and keep the state")
    void terminalStates() {
        Run completed = runs.start("a", Map.of());
        runs.checkpoint(completed.id(), "plan", Map.of("x", 1));
        Run done = runs.complete(completed.id(), Map.of("x", 1, "answer", "ok"));
        assertEquals(RunStatus.COMPLETED, done.status());
        assertTrue(done.stateJson().contains("answer"));

        Run failed = runs.start("b", Map.of());
        Run broken = runs.fail(failed.id(), "model returned 529 three times");
        assertEquals(RunStatus.FAILED, broken.status());
        assertEquals("model returned 529 three times", broken.error());
    }

    @Test
    @DisplayName("suspend then resume bumps the attempt and clears the error")
    void suspendAndResume() {
        Run run = runs.start("refund", Map.of("amount", 500));
        Run parked = runs.suspend(run.id(), "waiting for manager approval");
        assertEquals(RunStatus.SUSPENDED, parked.status());

        Run awake = runs.resume(run.id());
        assertEquals(RunStatus.RUNNING, awake.status());
        assertEquals(2, awake.attempt());
        assertEquals(null, awake.error());
    }

    @Test
    @DisplayName("a stale version is rejected instead of overwriting newer state")
    void optimisticLockRejectsStaleWrite() {
        Run run = runs.start("c", Map.of());
        Run version0 = store.find(run.id()).orElseThrow();

        Run v1 = version0.withState("{\"a\":1}", "plan", System.currentTimeMillis());
        assertTrue(store.update(v1, 0));

        Run stale = version0.withState("{\"a\":2}", "other", System.currentTimeMillis());
        assertFalse(store.update(stale, 0), "second writer with version 0 must lose");

        assertEquals("{\"a\":1}", store.find(run.id()).orElseThrow().stateJson());
    }

    @Test
    @DisplayName("a missing run fails loudly")
    void unknownRunIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> runs.get(UUID.randomUUID()));
    }

    @Test
    @DisplayName("re-recording the same node with the same state does not duplicate the timeline")
    void repeatedCheckpointIsIgnored() {
        Run run = runs.start("replay", Map.of());

        runs.checkpoint(run.id(), "plan", Map.of("k", "v"));
        Run afterReplay = runs.checkpoint(run.id(), "plan", Map.of("k", "v"));

        assertEquals(1, runs.timeline(run.id()).size(), "a replay must not append a checkpoint");
        assertEquals(1, afterReplay.version(), "and must not bump the run version either");

        runs.checkpoint(run.id(), "plan", Map.of("k", "changed"));
        assertEquals(2, runs.timeline(run.id()).size(), "a different state is a new checkpoint");
    }

    @Test
    @DisplayName("run and checkpoints survive a new store instance")
    void survivesRestart() {
        Run run = runs.start("durable", Map.of("k", "v"));
        runs.checkpoint(run.id(), "plan", Map.of("k", "v"));

        RunLifecycle restarted = new RunLifecycle(new JdbcRunStore(dataSource));
        Run reloaded = restarted.get(run.id());
        assertEquals("plan", reloaded.cursor());
        assertNotNull(restarted.resumePoint(run.id()).orElseThrow());
    }
}
