package dev.duragent.core.engine;

import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.RunLeaseStore;
import dev.duragent.core.store.RunStore;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The dispatch side of the runtime: who gets to execute which run, and for how long.
 *
 * <p>Claiming is a two-step protocol on purpose:
 * <ol>
 *   <li>list candidate runs (PENDING, or RUNNING with no live lease)</li>
 *   <li>try to take the lease for one of them</li>
 * </ol>
 * Two workers may list the same candidate; the lease decides. Losing that race is normal, so the
 * caller simply tries the next candidate.
 *
 * <p>There is no {@code SELECT ... FOR UPDATE SKIP LOCKED} anywhere: it is PostgreSQL/MySQL 8
 * specific and behaves differently on H2. The primary key does the same job on every database.
 */
public final class RunQueue {

    private static final int CANDIDATE_BATCH = 10;

    private final RunStore runs;
    private final RunLeaseStore leases;
    private final Clock clock;

    public RunQueue(RunStore runs, RunLeaseStore leases) {
        this(runs, leases, Clock.systemUTC());
    }

    public RunQueue(RunStore runs, RunLeaseStore leases, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Claims one run for this worker.
     *
     * @return the claimed run, promoted to RUNNING, or empty when there is nothing to do
     */
    public Optional<Run> claim(String workerId, Duration lease) {
        Objects.requireNonNull(workerId, "workerId");
        long now = clock.millis();
        long leasedUntil = now + lease.toMillis();

        for (UUID candidate : runs.findClaimable(now, CANDIDATE_BATCH)) {
            if (!leases.tryAcquire(candidate, workerId, leasedUntil, now)) {
                continue;
            }
            Run run = runs.find(candidate).orElse(null);
            if (run == null) {
                leases.release(candidate, workerId);
                continue;
            }
            if (run.status() == RunStatus.PENDING) {
                Run running = run.revise(RunStatus.RUNNING, run.stateJson(), run.cursor(), null, now);
                if (!runs.update(running, run.version())) {
                    // Someone else moved the run between the scan and the claim.
                    leases.release(candidate, workerId);
                    continue;
                }
                run = running;
            }
            return Optional.of(run);
        }
        return Optional.empty();
    }

    /** Extends the lease. Returns false when another worker has already taken the run. */
    public boolean heartbeat(String workerId, UUID runId, Duration lease) {
        long now = clock.millis();
        return leases.heartbeat(runId, workerId, now + lease.toMillis(), now);
    }

    public boolean release(String workerId, UUID runId) {
        return leases.release(runId, workerId);
    }
}
