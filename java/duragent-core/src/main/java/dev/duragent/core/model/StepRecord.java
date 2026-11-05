package dev.duragent.core.model;

import java.util.Objects;

/**
 * Immutable snapshot of one durable step's persisted state.
 *
 * <p>Timestamps are epoch milliseconds on purpose: {@code bigint} is portable across
 * H2, MySQL and PostgreSQL, and it sidesteps every JDBC timezone trap.
 *
 * @param runId            the workflow execution this step belongs to
 * @param stepKey          stable identity of the step inside the run (the idempotency key)
 * @param stepName         human readable step name, usually the graph node name
 * @param status           current lifecycle state
 * @param attempt          how many times this step has been started (1-based)
 * @param outputJson       serialized result, only meaningful when {@link StepStatus#SUCCEEDED}
 * @param error            last failure message, only meaningful when {@link StepStatus#FAILED}
 * @param startedAtMs      epoch millis when the current attempt started
 * @param finishedAtMs     epoch millis when the attempt finished, {@code null} while running
 * @param leaseExpiresAtMs epoch millis after which a running claim is considered abandoned
 */
public record StepRecord(
        String runId,
        String stepKey,
        String stepName,
        StepStatus status,
        int attempt,
        String outputJson,
        String error,
        long startedAtMs,
        Long finishedAtMs,
        long leaseExpiresAtMs) {

    public StepRecord {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepKey, "stepKey");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(status, "status");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
        }
    }

    /** Builds the record a worker writes when it claims a step. */
    public static StepRecord claim(String runId, String stepKey, String stepName, long nowMs, long leaseExpiresAtMs) {
        return new StepRecord(runId, stepKey, stepName, StepStatus.RUNNING, 1, null, null, nowMs, null, leaseExpiresAtMs);
    }

    /** True when a previous worker claimed this step but never finished it. */
    public boolean isLeaseExpired(long nowMs) {
        return status == StepStatus.RUNNING && leaseExpiresAtMs < nowMs;
    }

    public boolean isSucceeded() {
        return status == StepStatus.SUCCEEDED;
    }
}
