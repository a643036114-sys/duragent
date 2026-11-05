package dev.duragent.core.engine;

import dev.duragent.core.json.JsonCodec;
import dev.duragent.core.model.Checkpoint;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.RunConcurrencyException;
import dev.duragent.core.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Owns the run lifecycle: start, checkpoint after each node, suspend, resume, finish.
 *
 * <p>Every mutation is an optimistic read-modify-write on the {@code run} row. Losing the race
 * is normal (a worker and a resume can touch a run at the same time), so writes are retried a
 * few times before giving up loudly with {@link RunConcurrencyException}.
 *
 * <p>The checkpoint is written <em>before</em> the run row is updated. A crash in between leaves
 * a checkpoint that the run row does not mention yet, and resuming from the latest checkpoint is
 * still correct: the step journal decides what actually executed.
 */
public final class RunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycle.class);
    private static final int MAX_RETRIES = 3;

    private final RunStore store;
    private final JsonCodec json;
    private final Clock clock;

    public RunLifecycle(RunStore store) {
        this(store, new JsonCodec(), Clock.systemUTC());
    }

    public RunLifecycle(RunStore store, JsonCodec json, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Run start(String workflow, Map<String, Object> input) {
        long now = clock.millis();
        Run run = Run.start(UUID.randomUUID(), workflow, json.toJson(input), json.toJson(Map.of()), now);
        store.create(run);
        log.debug("started run {} workflow={}", run.id(), workflow);
        return run;
    }

    /** Records that {@code node} finished and what the graph state looks like now. */
    public Run checkpoint(UUID runId, String node, Map<String, Object> state) {
        String stateJson = json.toJson(state);
        int seq = store.latestCheckpoint(runId).map(Checkpoint::seq).orElse(0) + 1;
        store.appendCheckpoint(new Checkpoint(runId, seq, node, stateJson, clock.millis()));
        return mutate(runId, current -> current.withState(stateJson, node, clock.millis()));
    }

    public Run complete(UUID runId, Map<String, Object> finalState) {
        String stateJson = json.toJson(finalState);
        return mutate(runId, current -> current.revise(RunStatus.COMPLETED, stateJson,
                current.cursor(), null, clock.millis()));
    }

    public Run fail(UUID runId, String reason) {
        return mutate(runId, current -> current.revise(RunStatus.FAILED, current.stateJson(),
                current.cursor(), reason, clock.millis()));
    }

    public Run suspend(UUID runId, String reason) {
        return mutate(runId, current -> current.revise(RunStatus.SUSPENDED, current.stateJson(),
                current.cursor(), reason, clock.millis()));
    }

    /** Puts a parked run back to work and counts a new attempt. */
    public Run resume(UUID runId) {
        return mutate(runId, current -> current.resumeAt(clock.millis()));
    }

    public Optional<Checkpoint> resumePoint(UUID runId) {
        return store.latestCheckpoint(runId);
    }

    public List<Checkpoint> timeline(UUID runId) {
        return store.checkpoints(runId);
    }

    public Run get(UUID runId) {
        return store.find(runId).orElseThrow(
                () -> new IllegalArgumentException("unknown run: " + runId));
    }

    private Run mutate(UUID runId, UnaryOperator<Run> change) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Run current = get(runId);
            Run next = change.apply(current);
            if (store.update(next, current.version())) {
                return next;
            }
            log.debug("lost optimistic update on run {} (attempt {}), retrying", runId, attempt);
        }
        throw new RunConcurrencyException(runId.toString());
    }
}
