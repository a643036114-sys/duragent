package dev.duragent.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One execution of an agent graph.
 *
 * @param id          run id, also the {@code run_id} used by every step of this run
 * @param workflow    name of the workflow definition that produced this run
 * @param status      lifecycle state
 * @param inputJson   the original input, kept for replays and debugging
 * @param stateJson   latest known graph state
 * @param cursor      last node that finished, the natural resume point
 * @param error       last failure reason, only set for {@link RunStatus#FAILED}
 * @param attempt     how many times this run has been started (a resume bumps it)
 * @param version     optimistic concurrency token; every persisted change bumps it
 * @param createdAtMs epoch millis
 * @param updatedAtMs epoch millis
 */
public record Run(
        UUID id,
        String workflow,
        RunStatus status,
        String inputJson,
        String stateJson,
        String cursor,
        String error,
        int attempt,
        int version,
        long createdAtMs,
        long updatedAtMs) {

    public Run {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    /** A fresh run, version 0, already running. */
    public static Run start(UUID id, String workflow, String inputJson, String stateJson, long nowMs) {
        return new Run(id, workflow, RunStatus.RUNNING, inputJson, stateJson, null, null, 1, 0, nowMs, nowMs);
    }

    /**
     * Produces the next revision. Every persisted change goes through here, so the version
     * counter can never be forgotten by a caller.
     */
    public Run revise(RunStatus newStatus, String newStateJson, String newCursor, String newError, long nowMs) {
        return new Run(id, workflow, newStatus, inputJson, newStateJson, newCursor, newError,
                attempt, version + 1, createdAtMs, nowMs);
    }

    public Run withState(String newStateJson, String newCursor, long nowMs) {
        return revise(status, newStateJson, newCursor, error, nowMs);
    }

    public Run withStatus(RunStatus newStatus, long nowMs) {
        return revise(newStatus, stateJson, cursor, newStatus == RunStatus.FAILED ? error : null, nowMs);
    }

    /** Flips a suspended or failed run back to running and counts the new attempt. */
    public Run resumeAt(long nowMs) {
        return new Run(id, workflow, RunStatus.RUNNING, inputJson, stateJson, cursor, null,
                attempt + 1, version + 1, createdAtMs, nowMs);
    }

    public boolean isFinished() {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELED;
    }
}
