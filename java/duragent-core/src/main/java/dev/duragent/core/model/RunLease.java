package dev.duragent.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The lease that says "worker X is currently allowed to execute run Y".
 *
 * <p>Leases are the only thing standing between a crashed worker and a run that is never picked up
 * again, so they are deliberately short and renewable rather than long and optimistic.
 *
 * @param runId          the run being executed
 * @param workerId       who holds it
 * @param leasedUntilMs  epoch millis after which anyone may take over
 * @param claimedAtMs    when this lease was taken
 * @param heartbeatAtMs  last renewal
 */
public record RunLease(UUID runId, String workerId, long leasedUntilMs, long claimedAtMs, long heartbeatAtMs) {

    public RunLease {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workerId, "workerId");
    }
}
