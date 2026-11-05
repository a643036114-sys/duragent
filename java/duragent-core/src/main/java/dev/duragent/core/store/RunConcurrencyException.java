package dev.duragent.core.store;

/**
 * Raised when the run row kept moving under us: too many optimistic-update retries.
 */
public class RunConcurrencyException extends RuntimeException {

    public RunConcurrencyException(String runId) {
        super("run " + runId + " is being written concurrently; gave up after retries");
    }
}
