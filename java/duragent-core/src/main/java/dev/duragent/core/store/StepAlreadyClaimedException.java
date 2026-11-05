package dev.duragent.core.store;

/**
 * Raised when two workers race to claim the same step and this one lost.
 */
public class StepAlreadyClaimedException extends RuntimeException {

    public StepAlreadyClaimedException(String runId, String stepKey) {
        super("step already claimed: " + runId + "/" + stepKey);
    }
}
