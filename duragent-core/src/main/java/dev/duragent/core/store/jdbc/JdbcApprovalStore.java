package dev.duragent.core.store.jdbc;

import dev.duragent.core.model.Approval;
import dev.duragent.core.model.ApprovalStatus;
import dev.duragent.core.store.ApprovalStore;
import dev.duragent.core.store.StepStoreException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC implementation of {@link ApprovalStore}. */
public final class JdbcApprovalStore implements ApprovalStore {

    private static final String INSERT = """
            INSERT INTO run_approval (id, run_id, node, reason, status, requested_by,
                                      decided_by, comment_text, requested_at_ms, decided_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, run_id, node, reason, status, requested_by, decided_by, comment_text,
                   requested_at_ms, decided_at_ms
              FROM run_approval
             WHERE id = ?
            """;

    private static final String SELECT_PENDING_FOR_NODE = """
            SELECT id, run_id, node, reason, status, requested_by, decided_by, comment_text,
                   requested_at_ms, decided_at_ms
              FROM run_approval
             WHERE run_id = ? AND node = ? AND status = 'PENDING'
             ORDER BY requested_at_ms DESC
             LIMIT 1
            """;

    private static final String SELECT_LATEST_FOR_NODE = """
            SELECT id, run_id, node, reason, status, requested_by, decided_by, comment_text,
                   requested_at_ms, decided_at_ms
              FROM run_approval
             WHERE run_id = ? AND node = ?
             ORDER BY requested_at_ms DESC
             LIMIT 1
            """;

    private static final String SELECT_PENDING_FOR_RUN = """
            SELECT id, run_id, node, reason, status, requested_by, decided_by, comment_text,
                   requested_at_ms, decided_at_ms
              FROM run_approval
             WHERE run_id = ? AND status = 'PENDING'
             ORDER BY requested_at_ms ASC
            """;

    private static final String DECIDE = """
            UPDATE run_approval
               SET status = ?, decided_by = ?, comment_text = ?, decided_at_ms = ?
             WHERE id = ? AND status = 'PENDING'
            """;

    private final DataSource dataSource;

    public JdbcApprovalStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void create(Approval approval) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, approval.id().toString());
            ps.setString(2, approval.runId().toString());
            ps.setString(3, approval.node());
            ps.setString(4, approval.reason());
            ps.setString(5, approval.status().name());
            ps.setString(6, approval.requestedBy());
            ps.setLong(7, approval.requestedAtMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StepStoreException("create approval failed for run " + approval.runId(), e);
        }
    }

    @Override
    public Optional<Approval> find(UUID approvalId) {
        return queryOne(SELECT_BY_ID, ps -> ps.setString(1, approvalId.toString()));
    }

    @Override
    public Optional<Approval> findPending(UUID runId, String node) {
        return queryOne(SELECT_PENDING_FOR_NODE, ps -> {
            ps.setString(1, runId.toString());
            ps.setString(2, node);
        });
    }

    @Override
    public Optional<Approval> findLatest(UUID runId, String node) {
        return queryOne(SELECT_LATEST_FOR_NODE, ps -> {
            ps.setString(1, runId.toString());
            ps.setString(2, node);
        });
    }

    @Override
    public List<Approval> pending(UUID runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_PENDING_FOR_RUN)) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<Approval> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StepStoreException("pending approvals failed for run " + runId, e);
        }
    }

    @Override
    public boolean decide(UUID approvalId, ApprovalStatus decision, String by, String comment, long nowMs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(DECIDE)) {
            ps.setString(1, decision.name());
            ps.setString(2, by);
            ps.setString(3, comment);
            ps.setLong(4, nowMs);
            ps.setString(5, approvalId.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StepStoreException("decide approval failed for " + approvalId, e);
        }
    }

    private Optional<Approval> queryOne(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StepStoreException("approval query failed", e);
        }
    }

    private static Approval map(ResultSet rs) throws SQLException {
        long decided = rs.getLong("decided_at_ms");
        Long decidedAtMs = rs.wasNull() ? null : decided;
        return new Approval(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("run_id")),
                rs.getString("node"),
                rs.getString("reason"),
                ApprovalStatus.valueOf(rs.getString("status")),
                rs.getString("requested_by"),
                rs.getString("decided_by"),
                rs.getString("comment_text"),
                rs.getLong("requested_at_ms"),
                decidedAtMs);
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
