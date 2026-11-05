package dev.duragent.core.store.jdbc;

import dev.duragent.core.model.StepRecord;
import dev.duragent.core.model.StepStatus;
import dev.duragent.core.store.StepAlreadyClaimedException;
import dev.duragent.core.store.StepStore;
import dev.duragent.core.store.StepStoreException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Plain JDBC implementation of {@link StepStore}.
 *
 * <p>No ORM, no dialect-specific upsert syntax: the claim is a plain {@code INSERT} whose
 * primary key does the arbitration, and the takeover is a conditional {@code UPDATE} whose
 * row count says whether we won. Both statements are valid on H2, MySQL 8 and PostgreSQL.
 *
 * <p>Each call borrows one connection and returns it immediately; the engine never holds a
 * connection across a side effect, which is what keeps long LLM calls from starving the pool.
 */
public final class JdbcStepStore implements StepStore {

    private static final String SELECT_ONE = """
            SELECT run_id, step_key, step_name, status, attempt, output_json, error_text,
                   started_at_ms, finished_at_ms, lease_expires_at_ms
              FROM step_record
             WHERE run_id = ? AND step_key = ?
            """;

    private static final String INSERT_CLAIM = """
            INSERT INTO step_record
                (run_id, step_key, step_name, status, attempt, output_json, error_text,
                 started_at_ms, finished_at_ms, lease_expires_at_ms)
            VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, NULL, ?)
            """;

    private static final String RECLAIM_EXPIRED = """
            UPDATE step_record
               SET attempt = attempt + 1,
                   status = 'RUNNING',
                   started_at_ms = ?,
                   finished_at_ms = NULL,
                   lease_expires_at_ms = ?,
                   error_text = NULL
             WHERE run_id = ? AND step_key = ?
               AND status = 'RUNNING'
               AND lease_expires_at_ms < ?
            """;

    private static final String RETRY_FAILED = """
            UPDATE step_record
               SET attempt = attempt + 1,
                   status = 'RUNNING',
                   started_at_ms = ?,
                   finished_at_ms = NULL,
                   lease_expires_at_ms = ?,
                   error_text = NULL
             WHERE run_id = ? AND step_key = ? AND status = 'FAILED'
            """;

    private static final String COMPLETE = """
            UPDATE step_record
               SET status = 'SUCCEEDED',
                   output_json = ?,
                   finished_at_ms = ?,
                   error_text = NULL
             WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'
            """;

    private static final String FAIL = """
            UPDATE step_record
               SET status = 'FAILED',
                   error_text = ?,
                   finished_at_ms = ?
             WHERE run_id = ? AND step_key = ? AND status = 'RUNNING'
            """;

    private final DataSource dataSource;

    public JdbcStepStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<StepRecord> find(String runId, String stepKey) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ONE)) {
            ps.setString(1, runId);
            ps.setString(2, stepKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StepStoreException("find failed for " + runId + "/" + stepKey, e);
        }
    }

    @Override
    public void claim(StepRecord record) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_CLAIM)) {
            ps.setString(1, record.runId());
            ps.setString(2, record.stepKey());
            ps.setString(3, record.stepName());
            ps.setString(4, StepStatus.RUNNING.name());
            ps.setInt(5, record.attempt());
            ps.setLong(6, record.startedAtMs());
            ps.setLong(7, record.leaseExpiresAtMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (isIntegrityViolation(e)) {
                throw new StepAlreadyClaimedException(record.runId(), record.stepKey());
            }
            throw new StepStoreException("claim failed for " + record.runId() + "/" + record.stepKey(), e);
        }
    }

    @Override
    public boolean reclaimExpired(String runId, String stepKey, long nowMs, long newLeaseExpiresAtMs) {
        return conditionalUpdate(RECLAIM_EXPIRED, ps -> {
            ps.setLong(1, nowMs);
            ps.setLong(2, newLeaseExpiresAtMs);
            ps.setString(3, runId);
            ps.setString(4, stepKey);
            ps.setLong(5, nowMs);
        }, "reclaim failed for " + runId + "/" + stepKey);
    }

    @Override
    public boolean retry(String runId, String stepKey, long nowMs, long newLeaseExpiresAtMs) {
        return conditionalUpdate(RETRY_FAILED, ps -> {
            ps.setLong(1, nowMs);
            ps.setLong(2, newLeaseExpiresAtMs);
            ps.setString(3, runId);
            ps.setString(4, stepKey);
        }, "retry failed for " + runId + "/" + stepKey);
    }

    @Override
    public boolean complete(String runId, String stepKey, String outputJson, long finishedAtMs) {
        return conditionalUpdate(COMPLETE, ps -> {
            ps.setString(1, outputJson);
            ps.setLong(2, finishedAtMs);
            ps.setString(3, runId);
            ps.setString(4, stepKey);
        }, "complete failed for " + runId + "/" + stepKey);
    }

    @Override
    public boolean fail(String runId, String stepKey, String error, long finishedAtMs) {
        return conditionalUpdate(FAIL, ps -> {
            ps.setString(1, error);
            ps.setLong(2, finishedAtMs);
            ps.setString(3, runId);
            ps.setString(4, stepKey);
        }, "fail failed for " + runId + "/" + stepKey);
    }

    private boolean conditionalUpdate(String sql, StatementBinder binder, String errorMessage) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StepStoreException(errorMessage, e);
        }
    }

    private static StepRecord map(ResultSet rs) throws SQLException {
        long finished = rs.getLong("finished_at_ms");
        Long finishedAtMs = rs.wasNull() ? null : finished;
        return new StepRecord(
                rs.getString("run_id"),
                rs.getString("step_key"),
                rs.getString("step_name"),
                StepStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt"),
                rs.getString("output_json"),
                rs.getString("error_text"),
                rs.getLong("started_at_ms"),
                finishedAtMs,
                rs.getLong("lease_expires_at_ms"));
    }

    /** SQLState class 23 = integrity constraint violation, on every mainstream driver. */
    private static boolean isIntegrityViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
