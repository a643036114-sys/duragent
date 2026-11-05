package dev.duragent.core.store;

import dev.duragent.core.model.Budget;
import dev.duragent.core.model.BudgetEntry;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for run budgets.
 *
 * <p>The important method is {@link #charge}: it must be a single conditional statement that both
 * checks the limits and increments the spend. Splitting it into "read usage, compare, write usage"
 * lets two workers spend the last cent twice.
 */
public interface BudgetStore {

    /** Creates the budget row. Existing rows are left untouched. */
    void create(UUID runId, Budget budget, long startedAtMs);

    Optional<BudgetEntry> find(UUID runId);

    /**
     * Replaces the limits, keeping the spend. This is how a human raises the budget of a canceled
     * run before resuming it.
     */
    void updateLimits(UUID runId, Budget budget);

    /**
     * Atomically spends tokens, money and steps if - and only if - the result stays inside every
     * configured limit.
     *
     * @return true when the charge was accepted, false when it would have exceeded a limit
     */
    boolean charge(UUID runId, long tokens, long costMicros, int steps, long nowMs);
}
