package dev.duragent.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A request for a human decision, tied to one node of one run.
 *
 * <p>The approval lives in the database, not in a thread. That is the whole point: a run can wait
 * two days for a manager and cost nothing, because no process is sleeping on it.
 *
 * @param id            approval id, used by the UI/API that decides it
 * @param runId         run that is parked
 * @param node          step that wants permission
 * @param reason        what the human is being asked, in their language
 * @param status        current decision state
 * @param requestedBy   who or what asked (agent id, service name)
 * @param decidedBy     who decided
 * @param comment       free text from the decider
 * @param requestedAtMs epoch millis
 * @param decidedAtMs   epoch millis, null while pending
 */
public record Approval(
        UUID id,
        UUID runId,
        String node,
        String reason,
        ApprovalStatus status,
        String requestedBy,
        String decidedBy,
        String comment,
        long requestedAtMs,
        Long decidedAtMs) {

    public Approval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(status, "status");
    }

    public static Approval pending(UUID runId, String node, String reason, String requestedBy, long nowMs) {
        return new Approval(UUID.randomUUID(), runId, node, reason, ApprovalStatus.PENDING,
                requestedBy, null, null, nowMs, null);
    }

    public Approval decided(ApprovalStatus decision, String by, String comment, long nowMs) {
        return new Approval(id, runId, node, reason, decision, requestedBy, by, comment,
                requestedAtMs, nowMs);
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }
}
