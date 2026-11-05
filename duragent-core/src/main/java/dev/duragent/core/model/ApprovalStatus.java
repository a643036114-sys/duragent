package dev.duragent.core.model;

/** Lifecycle of a human approval request. */
public enum ApprovalStatus {

    /** Nobody has decided yet; the run is parked. */
    PENDING,

    /** A human allowed the run to continue. */
    APPROVED,

    /** A human refused; the run must not continue. */
    REJECTED,

    /** Nobody answered in time (enforced by a later milestone). */
    EXPIRED
}
