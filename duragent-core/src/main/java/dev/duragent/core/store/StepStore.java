package dev.duragent.core.store;

import dev.duragent.core.model.StepRecord;

import java.util.Optional;

/**
 * Persistence contract for durable steps.
 *
 * <p>Every method is idempotent-safe: the store never decides business semantics, it only
 * guarantees that one {@code (runId, stepKey)} pair can be claimed by exactly one worker.
 * That guarantee comes from the primary key plus a conditional {@code UPDATE ... WHERE}.
 *
 * <p>Implementations may be called concurrently from multiple worker threads and, in a
 * real deployment, from multiple processes.
 */
public interface StepStore {

    /** Reads the current record, or empty when the step has never been started. */
    Optional<StepRecord> find(String runId, String stepKey);

    /**
     * Inserts a fresh claim for a step that does not exist yet.
     *
     * @throws StepAlreadyClaimedException when another worker inserted the same key first
     */
    void claim(StepRecord record);

    /**
     * Takes over a step whose lease expired, i.e. whose worker died mid-execution.
     *
     * @return true when this caller won the takeover
     */
    boolean reclaimExpired(String runId, String stepKey, long nowMs, long newLeaseExpiresAtMs);

    /**
     * Moves a {@code FAILED} step back to {@code RUNNING} for another attempt.
     *
     * @return true when this caller won the retry
     */
    boolean retry(String runId, String stepKey, long nowMs, long newLeaseExpiresAtMs);

    /**
     * Stores the result and closes the step.
     *
     * @return false when the caller no longer owns the claim (someone reclaimed it)
     */
    boolean complete(String runId, String stepKey, String outputJson, long finishedAtMs);

    /**
     * Records a failure.
     *
     * @return false when the caller no longer owns the claim
     */
    boolean fail(String runId, String stepKey, String error, long finishedAtMs);
}
