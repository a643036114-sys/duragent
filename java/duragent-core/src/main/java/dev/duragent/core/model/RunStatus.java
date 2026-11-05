package dev.duragent.core.model;

/**
 * Lifecycle of a workflow run, i.e. one execution of an agent graph.
 *
 * <p>The run is the unit a human looks at ("why did ticket #42 get answered twice?"), while a
 * step is the unit the engine protects. Keeping them separate is what lets a run be parked for
 * two days without holding a thread.
 */
public enum RunStatus {

    /** Created, not started yet. */
    PENDING,

    /** At least one step has run and the run has not finished. */
    RUNNING,

    /** Parked on purpose: waiting for a human approval, an external event or a timer. */
    SUSPENDED,

    /** Reached the end of the graph. */
    COMPLETED,

    /** Stopped because a step exhausted its attempts. */
    FAILED,

    /** Stopped by a human or a budget guardrail. */
    CANCELED
}
