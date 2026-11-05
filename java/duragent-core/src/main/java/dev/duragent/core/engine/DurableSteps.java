package dev.duragent.core.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.duragent.core.json.JsonCodec;
import dev.duragent.core.model.StepRecord;
import dev.duragent.core.model.StepStatus;
import dev.duragent.core.store.StepAlreadyClaimedException;
import dev.duragent.core.store.StepStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs a side effect exactly once per {@code (run, step)} and replays its stored result on
 * every later attempt, including attempts made by a different process after a crash.
 *
 * <h2>The contract</h2>
 * <ol>
 *   <li>First call: claim the step in the database, execute the body, persist the result.</li>
 *   <li>Later calls: read the persisted result, never execute the body again.</li>
 *   <li>Crash mid-execution: the claim keeps a lease. Once the lease expires another worker
 *       takes the step over and executes it again ({@code attempt} increments).</li>
 * </ol>
 *
 * <h2>What this does and does not guarantee</h2>
 * The body is executed <em>at least once</em> but its recorded result is stored <em>at most
 * once</em>. A crash in the window between "side effect happened" and "result committed"
 * therefore re-runs the body. To get effectively-once delivery, pass the step key to the
 * downstream system as its idempotency key — that is exactly what payment APIs, email
 * providers and most LLM gateways support.
 */
public final class DurableSteps {

    private static final Logger log = LoggerFactory.getLogger(DurableSteps.class);

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);

    private final StepStore store;
    private final JsonCodec json;
    private final Clock clock;
    private final Duration lease;

    public DurableSteps(StepStore store) {
        this(store, new JsonCodec(), Clock.systemUTC(), DEFAULT_LEASE);
    }

    public DurableSteps(StepStore store, JsonCodec json, Clock clock, Duration lease) {
        this.store = Objects.requireNonNull(store, "store");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
    }

    /** Runs a step without input. */
    public <O> O run(UUID runId, String stepName, Class<O> resultType, Supplier<O> body) {
        return run(runId, stepName, null, resultType, ignored -> body.get());
    }

    /** Runs a step without input, keeping generic result types (e.g. {@code List<Msg>}). */
    public <O> O run(UUID runId, String stepName, TypeReference<O> resultType, Supplier<O> body) {
        return run(runId, stepName, null, resultType, ignored -> body.get());
    }

    /** Runs a step with input and a concrete result type. */
    public <I, O> O run(UUID runId, String stepName, I input, Class<O> resultType, Function<I, O> body) {
        Objects.requireNonNull(resultType, "resultType");
        return execute(runId, stepName, input, body, outputJson -> json.fromJson(outputJson, resultType));
    }

    /** Runs a step with input, keeping generic result types. */
    public <I, O> O run(UUID runId, String stepName, I input, TypeReference<O> resultType, Function<I, O> body) {
        Objects.requireNonNull(resultType, "resultType");
        return execute(runId, stepName, input, body, outputJson -> json.fromJson(outputJson, resultType));
    }

    private <I, O> O execute(UUID runId, String stepName, I input,
                             Function<I, O> body, Decoder<O> decoder) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(body, "body");

        String id = runId.toString();
        String key = StepKeys.normalize(stepName);
        long now = clock.millis();
        long leaseUntil = now + lease.toMillis();

        Optional<StepRecord> existing = store.find(id, key);
        if (existing.isPresent()) {
            StepRecord record = existing.get();
            switch (record.status()) {
                case SUCCEEDED -> {
                    log.debug("replaying {}/{} attempt={}", id, key, record.attempt());
                    return decoder.decode(record.outputJson());
                }
                case RUNNING -> {
                    if (!record.isLeaseExpired(now)) {
                        throw new StepInProgressException(id, key);
                    }
                    if (!store.reclaimExpired(id, key, now, leaseUntil)) {
                        throw new StepInProgressException(id, key);
                    }
                    log.warn("taking over expired step {}/{} previousAttempt={}", id, key, record.attempt());
                }
                case FAILED -> {
                    if (!store.retry(id, key, now, leaseUntil)) {
                        throw new StepInProgressException(id, key);
                    }
                    log.info("retrying failed step {}/{} attempt={}", id, key, record.attempt() + 1);
                }
            }
        } else {
            try {
                store.claim(StepRecord.claim(id, key, key, now, leaseUntil));
                log.debug("claimed {}/{}", id, key);
            } catch (StepAlreadyClaimedException raced) {
                // Another worker inserted between our SELECT and INSERT. Replay it if it is
                // already done, otherwise let the caller decide what to do.
                return store.find(id, key)
                        .filter(StepRecord::isSucceeded)
                        .map(record -> decoder.decode(record.outputJson()))
                        .orElseThrow(() -> new StepInProgressException(id, key));
            }
        }

        O output;
        try {
            output = body.apply(input);
        } catch (Throwable failure) {
            Throwable abort = findAbort(failure);
            if (abort != null) {
                // The step never produced a result and must not look retryable. Releasing the
                // claim lets a later resume (with a raised budget) execute it again.
                store.abort(id, key);
                log.warn("aborted step {}/{}: {}", id, key, abort.getMessage());
                if (abort instanceof RuntimeException runtime) {
                    throw runtime;
                }
            }
            store.fail(id, key, describe(failure), clock.millis());
            if (failure instanceof Error error) {
                throw error;
            }
            throw new StepExecutionException(id, key, failure);
        }

        String outputJson = json.toJson(output);
        if (!store.complete(id, key, outputJson, clock.millis())) {
            throw new IllegalStateException(
                    "lost ownership of step " + id + "/" + key + " while completing it");
        }
        return output;
    }

    /**
     * Abort signals often arrive wrapped: a node runs on another thread, so a
     * {@code CompletableFuture} wraps the original exception in a completion wrapper. Look through
     * the cause chain so a budget stop is recognised wherever it was raised.
     */
    private static Throwable findAbort(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            if (current instanceof AbortSignal) {
                return current;
            }
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return null;
            }
            current = cause;
        }
        return null;
    }

    private static String describe(Throwable failure) {        String message = failure.getMessage();
        String text = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }

    @FunctionalInterface
    private interface Decoder<O> {
        O decode(String outputJson);
    }
}
