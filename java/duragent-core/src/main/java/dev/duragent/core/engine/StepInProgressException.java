package dev.duragent.core.engine;

/**
 * The step is owned by another worker right now.
 *
 * <p>Deliberately fail-fast rather than silently blocking: a stalled side effect must not
 * turn into a stalled thread. Waiting/re-joining is a scheduling concern and belongs to the
 * runner (see {@code docs/roadmap.md}, step 4), not to the durable step primitive.
 */
public class StepInProgressException extends RuntimeException {

    private final String runId;
    private final String stepKey;

    public StepInProgressException(String runId, String stepKey) {
        super("step is already running in another worker: " + runId + "/" + stepKey);
        this.runId = runId;
        this.stepKey = stepKey;
    }

    public String runId() {
        return runId;
    }

    public String stepKey() {
        return stepKey;
    }
}
