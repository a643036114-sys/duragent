package dev.duragent.core.engine;

import dev.duragent.core.model.Budget;
import dev.duragent.core.model.BudgetEntry;
import dev.duragent.core.store.BudgetStore;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spend control for a run.
 *
 * <pre>{@code
 * budgets.set(runId, Budget.of(200_000, 500_000, 30, Duration.ofMinutes(10)));  // 0.50 currency
 * ...
 * budgets.charge(runId, 1_842, 4_000, 0);   // 1842 tokens, 0.004
 * }</pre>
 *
 * <p>Charging is fail-fast and atomic. When a charge does not fit, nothing is recorded and
 * {@link BudgetExceededException} is thrown; the runner turns that into a canceled run with its
 * state intact, so a human can raise the budget and resume.
 *
 * <p>Wall-clock is checked, not charged: it moves on its own, and there is nothing to add up.
 */
public final class Budgets {

    private final BudgetStore store;
    private final Clock clock;

    public Budgets(BudgetStore store) {
        this(store, Clock.systemUTC());
    }

    public Budgets(BudgetStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Attaches limits to a run. The first call wins; later calls are ignored. */
    public Optional<BudgetEntry> set(UUID runId, Budget budget) {
        store.create(runId, budget, clock.millis());
        return store.find(runId);
    }

    public Optional<BudgetEntry> usage(UUID runId) {
        return store.find(runId);
    }

    /**
     * Replaces the limits without touching the spend, so a canceled run can be resumed with a
     * larger budget instead of being restarted from zero.
     */
    public Optional<BudgetEntry> raise(UUID runId, Budget newLimits) {
        store.updateLimits(runId, newLimits);
        return store.find(runId);
    }

    /**
     * Spends tokens, money and steps in one atomic step.
     *
     * @return the updated ledger, or empty when the run has no budget attached
     * @throws BudgetExceededException when the charge would cross a limit; nothing is recorded
     */
    public Optional<BudgetEntry> charge(UUID runId, long tokens, long costMicros, int steps) {
        if (tokens < 0 || costMicros < 0 || steps < 0) {
            throw new IllegalArgumentException("a charge cannot be negative");
        }
        Optional<BudgetEntry> before = store.find(runId);
        if (before.isEmpty()) {
            return Optional.empty();
        }
        if (store.charge(runId, tokens, costMicros, steps, clock.millis())) {
            return store.find(runId);
        }
        throw exceeded(runId, before.get(), tokens, costMicros, steps);
    }

    /**
     * @throws BudgetExceededException when the run has outlived its wall-clock limit
     */
    public void checkWallClock(UUID runId) {
        store.find(runId).ifPresent(entry -> {
            Long max = entry.budget().maxWallClockMs();
            if (max == null) {
                return;
            }
            long elapsed = entry.elapsedMillis(clock.millis());
            if (elapsed > max) {
                throw new BudgetExceededException(runId.toString(), "wall-clock", max, elapsed);
            }
        });
    }

    private BudgetExceededException exceeded(
            UUID runId, BudgetEntry entry, long tokens, long costMicros, int steps) {
        Budget budget = entry.budget();
        if (budget.maxTokens() != null && entry.usedTokens() + tokens > budget.maxTokens()) {
            return new BudgetExceededException(runId.toString(), "tokens",
                    budget.maxTokens(), entry.usedTokens() + tokens);
        }
        if (budget.maxCostMicros() != null && entry.usedCostMicros() + costMicros > budget.maxCostMicros()) {
            return new BudgetExceededException(runId.toString(), "cost",
                    budget.maxCostMicros(), entry.usedCostMicros() + costMicros);
        }
        if (budget.maxSteps() != null && entry.usedSteps() + steps > budget.maxSteps()) {
            return new BudgetExceededException(runId.toString(), "steps",
                    budget.maxSteps(), entry.usedSteps() + steps);
        }
        // The database rejected the charge but no single limit explains it: treat it as a race
        // with another worker, which is still a budget stop from this caller's point of view.
        return new BudgetExceededException(runId.toString(), "concurrent", 0, 0);
    }
}
