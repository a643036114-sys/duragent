package dev.duragent.core.engine;

/**
 * Thrown when a human refused the action. The run must stop; this is not retryable.
 */
public class ApprovalRejectedException extends RuntimeException {

    private final String runId;
    private final String node;

    public ApprovalRejectedException(String runId, String node, String decidedBy, String comment) {
        super("approval rejected for " + runId + "/" + node
                + (decidedBy == null ? "" : " by " + decidedBy)
                + (comment == null ? "" : ": " + comment));
        this.runId = runId;
        this.node = node;
    }

    public String runId() {
        return runId;
    }

    public String node() {
        return node;
    }
}
