package dev.duragent.core.engine;

import dev.duragent.core.model.Run;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One process competing for runs.
 *
 * <p>A worker claims a run, renews the lease in the background while the executor works, and
 * releases the lease when it is done. If the process dies, the lease simply stops being renewed
 * and another worker picks the run up after it expires; a node that already ran replays from the
 * journal instead of repeating its side effect.
 *
 * <p>The delivery guarantee is at-least-once, never exactly-once. Duplicate execution is bounded
 * by the lease length, and made harmless by the step journal.
 */
public final class Worker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final String workerId;
    private final RunQueue queue;
    private final RunExecutor executor;
    private final Duration lease;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public Worker(String workerId, RunQueue queue, RunExecutor executor,
                  Duration lease, Duration heartbeatEvery) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.lease = Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(heartbeatEvery, "heartbeatEvery");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (heartbeatEvery.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("heartbeat must be shorter than the lease");
        }

        this.heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "duragent-heartbeat-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeat.scheduleAtFixedRate(this::renewLeases,
                heartbeatEvery.toMillis(), heartbeatEvery.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Claims and executes at most one run.
     *
     * @return false when the queue was empty
     */
    public boolean pollOnce() {
        if (closed.get()) {
            return false;
        }
        Optional<Run> claimed = queue.claim(workerId, lease);
        if (claimed.isEmpty()) {
            return false;
        }

        Run run = claimed.get();
        inFlight.add(run.id());
        try {
            executor.execute(run);
        } catch (Exception failure) {
            // The run keeps its lease: retrying immediately would just burn the same wall clock,
            // and the lease expiring is a natural backoff. The executor is responsible for
            // marking the run failed when it wants that to be permanent.
            log.warn("worker {} failed on run {}: {}", workerId, run.id(), failure.getMessage());
        } finally {
            inFlight.remove(run.id());
            queue.release(workerId, run.id());
        }
        return true;
    }

    public String workerId() {
        return workerId;
    }

    public int inFlight() {
        return inFlight.size();
    }

    private void renewLeases() {
        for (UUID runId : inFlight) {
            try {
                if (!queue.heartbeat(workerId, runId, lease)) {
                    log.warn("lost the lease on run {}; another worker has taken over", runId);
                }
            } catch (RuntimeException e) {
                log.warn("heartbeat failed for run {}: {}", runId, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            heartbeat.shutdownNow();
        }
    }
}
