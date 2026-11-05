package dev.duragent.core.engine;

/**
 * Thrown when a run reaches a node that needs a human decision.
 *
 * <p>This is control flow, not an error: the run is parked as {@code SUSPENDED} and will be
 * resumed after {@code approve}. Runners must not mark such a run as failed.
 */
public class ApprovalRequiredException extends RuntimeException {

    private final String approvalId;
    private final String runId;
    private final String node;

    public ApprovalRequiredException(String approvalId, String runId, String node, String reason) {
        super("approval required for " + runId + "/" + node + ": " + reason);
        this.approvalId = approvalId;
        this.runId = runId;
        this.node = node;
    }

    public String approvalId() {
        return approvalId;
    }

    public String runId() {
        return runId;
    }

    public String node() {
        return node;
    }
}
