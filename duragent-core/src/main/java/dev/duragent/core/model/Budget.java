package dev.duragent.core.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard limits for one run. Every field is nullable and {@code null} means "no limit".
 *
 * <p>Money is stored as integer micro-units (1 CNY = 1_000_000) rather than {@code double}.
 * Accumulating a few thousand model calls in {@code double} is how a budget silently drifts.
 *
 * @param maxTokens        total model tokens allowed
 * @param maxCostMicros    total cost allowed, in millionths of the currency unit
 * @param maxSteps         number of steps allowed
 * @param maxWallClockMs   wall-clock milliseconds from the start of the run
 */
public record Budget(Long maxTokens, Long maxCostMicros, Integer maxSteps, Long maxWallClockMs) {

    public Budget {
        requirePositive(maxTokens, "maxTokens");
        requirePositive(maxCostMicros, "maxCostMicros");
        requirePositive(maxSteps, "maxSteps");
        requirePositive(maxWallClockMs, "maxWallClockMs");
    }

    public static Budget unlimited() {
        return new Budget(null, null, null, null);
    }

    public static Budget of(long maxTokens, long maxCostMicros, int maxSteps, Duration maxWallClock) {
        Objects.requireNonNull(maxWallClock, "maxWallClock");
        return new Budget(maxTokens, maxCostMicros, maxSteps, maxWallClock.toMillis());
    }

    public Budget withMaxTokens(long value) {
        return new Budget(value, maxCostMicros, maxSteps, maxWallClockMs);
    }

    public Budget withMaxCostMicros(long value) {
        return new Budget(maxTokens, value, maxSteps, maxWallClockMs);
    }

    public Budget withMaxSteps(int value) {
        return new Budget(maxTokens, maxCostMicros, value, maxWallClockMs);
    }

    public Budget withMaxWallClock(Duration value) {
        return new Budget(maxTokens, maxCostMicros, maxSteps, value.toMillis());
    }

    public boolean isUnlimited() {
        return maxTokens == null && maxCostMicros == null && maxSteps == null && maxWallClockMs == null;
    }

    private static void requirePositive(Number value, String name) {
        if (value != null && value.longValue() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
