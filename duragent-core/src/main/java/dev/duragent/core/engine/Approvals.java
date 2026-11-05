package dev.duragent.core.engine;

import dev.duragent.core.model.Approval;
import dev.duragent.core.model.ApprovalStatus;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.ApprovalStore;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Human-in-the-loop approvals.
 *
 * <p>The flow is deliberately boring:
 * <ol>
 *   <li>a node is about to do something irreversible (refund, delete, send)</li>
 *   <li>{@link #requireApproval} parks the run as {@code SUSPENDED} and throws
 *       {@link ApprovalRequiredException} - the process is free to exit</li>
 *   <li>days later a human calls {@link #approve}; the run flips back to {@code RUNNING}</li>
 *   <li>invoking the run again replays the journal and only the gated node executes</li>
 * </ol>
 *
 * <p>No thread, no timer, no in-memory state: the approval row is the whole mechanism.
 */
public final class Approvals {

    private final ApprovalStore store;
    private final RunLifecycle runs;
    private final Clock clock;

    public Approvals(ApprovalStore store, RunLifecycle runs) {
        this(store, runs, Clock.systemUTC());
    }

    public Approvals(ApprovalStore store, RunLifecycle runs, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Parks the run on first call and returns the pending request on later calls.
     * Safe to call repeatedly: at most one PENDING row exists per {@code (run, node)}.
     */
    public Approval request(UUID runId, String node, String reason, String requestedBy) {
        Optional<Approval> existing = store.findPending(runId, node);
        if (existing.isPresent()) {
            return existing.get();
        }
        Approval approval = Approval.pending(runId, node, reason, requestedBy, clock.millis());
        store.create(approval);

        Run run = runs.get(runId);
        if (run.status() == RunStatus.RUNNING) {
            runs.suspend(runId, "approval required: " + reason);
        }
        return approval;
    }

    /**
     * Gate for runners: returns normally when the node is approved, throws otherwise.
     *
     * @throws ApprovalRequiredException when the run must be parked
     * @throws ApprovalRejectedException when a human already said no
     */
    public void requireApproval(UUID runId, String node, String reason, String requestedBy) {
        Optional<Approval> latest = store.findLatest(runId, node);
        if (latest.isPresent()) {
            Approval approval = latest.get();
            if (approval.status() == ApprovalStatus.APPROVED) {
                return;
            }
            if (approval.status() == ApprovalStatus.REJECTED) {
                throw new ApprovalRejectedException(runId.toString(), node,
                        approval.decidedBy(), approval.comment());
            }
            if (approval.status() == ApprovalStatus.PENDING) {
                throw new ApprovalRequiredException(approval.id().toString(), runId.toString(),
                        node, approval.reason());
            }
            // EXPIRED falls through and asks again.
        }

        Approval fresh = request(runId, node, reason, requestedBy);
        throw new ApprovalRequiredException(fresh.id().toString(), runId.toString(), node, reason);
    }

    public Approval approve(UUID approvalId, String by, String comment) {
        return decide(approvalId, ApprovalStatus.APPROVED, by, comment);
    }

    public Approval reject(UUID approvalId, String by, String comment) {
        return decide(approvalId, ApprovalStatus.REJECTED, by, comment);
    }

    public Optional<Approval> find(UUID approvalId) {
        return store.find(approvalId);
    }

    public List<Approval> pending(UUID runId) {
        return store.pending(runId);
    }

    private Approval decide(UUID approvalId, ApprovalStatus decision, String by, String comment) {
        boolean won = store.decide(approvalId, decision, by, comment, clock.millis());
        Approval current = store.find(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("unknown approval: " + approvalId));
        if (!won) {
            // Someone else decided first. Stay idempotent: report the stored decision.
            return current;
        }

        Run run = runs.get(current.runId());
        if (decision == ApprovalStatus.APPROVED && run.status() == RunStatus.SUSPENDED) {
            runs.resume(run.id());
        } else if (decision == ApprovalStatus.REJECTED && !run.isFinished()) {
            runs.fail(run.id(), "approval rejected for node '" + current.node() + "' by " + by);
        }
        return current;
    }
}
