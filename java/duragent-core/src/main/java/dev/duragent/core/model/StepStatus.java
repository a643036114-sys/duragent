package dev.duragent.core.model;

/**
 * Lifecycle of a single durable step.
 *
 * <p>A step is the unit that owns a side effect (an LLM call, a payment, an email).
 * Its record lives in the database, which is what makes the effect survivable across
 * process restarts.
 */
public enum StepStatus {

    /** The step is claimed by a worker and currently executing (or the worker died). */
    RUNNING,

    /** The step produced a result. The result is persisted and will be replayed verbatim. */
    SUCCEEDED,

    /** The step threw. It may be retried, which bumps the attempt counter. */
    FAILED
}
