package dev.duragent.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Graph state snapshot taken after a node finished.
 *
 * <p>This is the "where in the graph were we" record. The step journal answers the other
 * question - "did this side effect already happen" - and the two together are what make a
 * run resumable without re-executing work.
 *
 * @param runId       owning run
 * @param seq         1-based, monotonically increasing per run
 * @param node        graph node that just finished
 * @param stateJson   serialized graph state at that point
 * @param createdAtMs epoch millis
 */
public record Checkpoint(UUID runId, int seq, String node, String stateJson, long createdAtMs) {

    public Checkpoint {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(node, "node");
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1, was " + seq);
        }
    }
}
