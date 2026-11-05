package dev.duragent.core.store;

import dev.duragent.core.model.Checkpoint;
import dev.duragent.core.model.Run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for runs and their checkpoints.
 *
 * <p>Updates are optimistic: a caller passes the version it read, and the store returns
 * {@code false} when someone else wrote first. The engine turns that into a retry, which keeps
 * two workers from silently overwriting each other's graph state.
 */
public interface RunStore {

    void create(Run run);

    Optional<Run> find(UUID runId);

    /**
     * @param expectedVersion the version the caller read
     * @return true when the row was updated, false when it had moved on
     */
    boolean update(Run run, int expectedVersion);

    void appendCheckpoint(Checkpoint checkpoint);

    Optional<Checkpoint> latestCheckpoint(UUID runId);

    List<Checkpoint> checkpoints(UUID runId);
}
