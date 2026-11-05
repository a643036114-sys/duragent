package dev.duragent.core.engine;

/**
 * Marker for exceptions that mean "stop this run", not "this step failed".
 *
 * <p>An abort signal must not poison the step journal: recording it as {@code FAILED} would make
 * the step look retryable, and a later resume would silently skip the work it never did. The
 * engine therefore releases the claim instead of failing it, so raising the budget and resuming
 * re-executes exactly the step that was interrupted.
 */
public interface AbortSignal {
}
