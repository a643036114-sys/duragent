package dev.duragent.core.store;

import dev.duragent.core.model.RunLease;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for execution leases.
 *
 * <p>{@link #tryAcquire} is the arbitration: an insert that fails on the primary key is followed
 * by a conditional update, so exactly one worker wins a free or expired lease.
 */
public interface RunLeaseStore {

    Optional<RunLease> find(UUID runId);

    /**
     * @return true when this worker now holds the lease
     */
    boolean tryAcquire(UUID runId, String workerId, long leasedUntilMs, long nowMs);

    /**
     * @return false when the lease was taken over and this worker must stop trusting its claim
     */
    boolean heartbeat(UUID runId, String workerId, long leasedUntilMs, long nowMs);

    /** Gives the lease up early, e.g. when the run is finished or parked. */
    boolean release(UUID runId, String workerId);
}
