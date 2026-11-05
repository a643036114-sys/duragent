package dev.duragent.core.engine;

/**
 * Wraps whatever the step body threw, after the failure has been persisted.
 *
 * <p>Keeping the original exception as the cause matters: the engine's job is to record and
 * retry, not to hide the stack trace a developer needs.
 */
public class StepExecutionException extends RuntimeException {

    private final String runId;
    private final String stepKey;

    public StepExecutionException(String runId, String stepKey, Throwable cause) {
        super("step failed: " + runId + "/" + stepKey, cause);
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
