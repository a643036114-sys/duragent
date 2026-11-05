package dev.duragent.core.store;

import dev.duragent.core.model.Approval;
import dev.duragent.core.model.ApprovalStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for approval requests.
 *
 * <p>{@link #decide} is a conditional update: only the first decision on a {@code PENDING} row
 * wins, so two managers clicking "approve" and "reject" at the same time cannot both succeed.
 */
public interface ApprovalStore {

    void create(Approval approval);

    Optional<Approval> find(UUID approvalId);

    Optional<Approval> findPending(UUID runId, String node);

    /** Latest request for this node, whatever its state - used to decide whether to ask again. */
    Optional<Approval> findLatest(UUID runId, String node);

    List<Approval> pending(UUID runId);

    /**
     * @return true when this caller made the decision, false when the row was already decided
     */
    boolean decide(UUID approvalId, ApprovalStatus decision, String by, String comment, long nowMs);
}
