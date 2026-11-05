package dev.duragent.core.engine;

/**
 * Thrown when a run tries to spend more than one of its limits allows.
 *
 * <p>This is a guardrail, not a bug: the runner should cancel the run, keep its state, and let a
 * human decide whether to raise the budget and resume.
 */
public class BudgetExceededException extends RuntimeException implements AbortSignal {

    /** Which limit was hit: {@code tokens}, {@code cost}, {@code steps} or {@code wall-clock}. */
    private final String limit;
    private final long allowed;
    private final long attempted;
    private final String runId;

    public BudgetExceededException(String runId, String limit, long allowed, long attempted) {
        super("run " + runId + " exceeded its " + limit + " budget: " + attempted + " > " + allowed);
        this.runId = runId;
        this.limit = limit;
        this.allowed = allowed;
        this.attempted = attempted;
    }

    public String runId() {
        return runId;
    }

    public String limit() {
        return limit;
    }

    public long allowed() {
        return allowed;
    }

    public long attempted() {
        return attempted;
    }
}
