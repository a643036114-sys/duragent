package dev.duragent.core.store.jdbc;

import dev.duragent.core.model.Budget;
import dev.duragent.core.model.BudgetEntry;
import dev.duragent.core.store.BudgetStore;
import dev.duragent.core.store.StepStoreException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC implementation of {@link BudgetStore}.
 *
 * <p>The charge is one statement: the {@code WHERE} clause holds every limit, so the database
 * itself decides whether the spend is allowed. There is no read-then-write window in which two
 * workers could both pass the check and then both increment.
 */
public final class JdbcBudgetStore implements BudgetStore {

    private static final String INSERT = """
            INSERT INTO run_budget (run_id, max_tokens, max_cost_micros, max_steps, max_wall_clock_ms,
                                    used_tokens, used_cost_micros, used_steps, started_at_ms, updated_at_ms)
            VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?, ?)
            """;

    private static final String SELECT = """
            SELECT run_id, max_tokens, max_cost_micros, max_steps, max_wall_clock_ms,
                   used_tokens, used_cost_micros, used_steps, started_at_ms, updated_at_ms
              FROM run_budget
             WHERE run_id = ?
            """;

    private static final String CHARGE = """
            UPDATE run_budget
               SET used_tokens = used_tokens + ?,
                   used_cost_micros = used_cost_micros + ?,
                   used_steps = used_steps + ?,
                   updated_at_ms = ?
             WHERE run_id = ?
               AND (max_tokens IS NULL OR used_tokens + ? <= max_tokens)
               AND (max_cost_micros IS NULL OR used_cost_micros + ? <= max_cost_micros)
               AND (max_steps IS NULL OR used_steps + ? <= max_steps)
            """;

    private static final String UPDATE_LIMITS = """
            UPDATE run_budget
               SET max_tokens = ?, max_cost_micros = ?, max_steps = ?, max_wall_clock_ms = ?,
                   updated_at_ms = ?
             WHERE run_id = ?
            """;

    private final DataSource dataSource;

    public JdbcBudgetStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void create(UUID runId, Budget budget, long startedAtMs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, runId.toString());
            setNullableLong(ps, 2, budget.maxTokens());
            setNullableLong(ps, 3, budget.maxCostMicros());
            setNullableInt(ps, 4, budget.maxSteps());
            setNullableLong(ps, 5, budget.maxWallClockMs());
            ps.setLong(6, startedAtMs);
            ps.setLong(7, startedAtMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isIntegrityViolation(e)) {
                // A budget is already attached; the first one wins.
                return;
            }
            throw new StepStoreException("create budget failed for run " + runId, e);
        }
    }

    @Override
    public Optional<BudgetEntry> find(UUID runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT)) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Budget budget = new Budget(
                        nullableLong(rs, "max_tokens"),
                        nullableLong(rs, "max_cost_micros"),
                        nullableInt(rs, "max_steps"),
                        nullableLong(rs, "max_wall_clock_ms"));
                return Optional.of(new BudgetEntry(
                        UUID.fromString(rs.getString("run_id")),
                        budget,
                        rs.getLong("used_tokens"),
                        rs.getLong("used_cost_micros"),
                        rs.getInt("used_steps"),
                        rs.getLong("started_at_ms"),
                        rs.getLong("updated_at_ms")));
            }
        } catch (SQLException e) {
            throw new StepStoreException("find budget failed for run " + runId, e);
        }
    }

    @Override
    public boolean charge(UUID runId, long tokens, long costMicros, int steps, long nowMs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(CHARGE)) {
            ps.setLong(1, tokens);
            ps.setLong(2, costMicros);
            ps.setInt(3, steps);
            ps.setLong(4, nowMs);
            ps.setString(5, runId.toString());
            ps.setLong(6, tokens);
            ps.setLong(7, costMicros);
            ps.setInt(8, steps);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StepStoreException("charge failed for run " + runId, e);
        }
    }

    @Override
    public void updateLimits(UUID runId, Budget budget) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_LIMITS)) {
            setNullableLong(ps, 1, budget.maxTokens());
            setNullableLong(ps, 2, budget.maxCostMicros());
            setNullableInt(ps, 3, budget.maxSteps());
            setNullableLong(ps, 4, budget.maxWallClockMs());
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, runId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StepStoreException("update budget failed for run " + runId, e);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static boolean isIntegrityViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
