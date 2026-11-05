package dev.duragent.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The budget row of a run: the limits plus what has been spent so far.
 *
 * <p>Spend is recorded only when a charge fits inside the limits, so the numbers always describe
 * money that was actually authorised. An overrun is rejected, not billed and rolled back.
 *
 * @param runId          owning run
 * @param budget         limits in force
 * @param usedTokens     tokens spent
 * @param usedCostMicros money spent, in millionths of the currency unit
 * @param usedSteps      steps started
 * @param startedAtMs    when the budget clock started
 * @param updatedAtMs    last charge
 */
public record BudgetEntry(
        UUID runId,
        Budget budget,
        long usedTokens,
        long usedCostMicros,
        int usedSteps,
        long startedAtMs,
        long updatedAtMs) {

    public BudgetEntry {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(budget, "budget");
    }

    public long elapsedMillis(long nowMs) {
        return Math.max(0, nowMs - startedAtMs);
    }

    /** 0..1, how much of the cost limit has been consumed; 0 when unlimited. */
    public double costRatio() {
        Long max = budget.maxCostMicros();
        return max == null ? 0d : Math.min(1d, (double) usedCostMicros / max);
    }
}
