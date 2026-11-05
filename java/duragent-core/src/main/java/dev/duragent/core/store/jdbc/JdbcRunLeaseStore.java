package dev.duragent.core.store.jdbc;

import dev.duragent.core.model.RunLease;
import dev.duragent.core.store.RunLeaseStore;
import dev.duragent.core.store.StepStoreException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC implementation of {@link RunLeaseStore}. */
public final class JdbcRunLeaseStore implements RunLeaseStore {

    private static final String INSERT = """
            INSERT INTO run_lease (run_id, worker_id, leased_until_ms, claimed_at_ms, heartbeat_at_ms)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT = """
            SELECT run_id, worker_id, leased_until_ms, claimed_at_ms, heartbeat_at_ms
              FROM run_lease
             WHERE run_id = ?
            """;

    private static final String TAKE_OVER = """
            UPDATE run_lease
               SET worker_id = ?, leased_until_ms = ?, claimed_at_ms = ?, heartbeat_at_ms = ?
             WHERE run_id = ? AND leased_until_ms < ?
            """;

    private static final String HEARTBEAT = """
            UPDATE run_lease
               SET leased_until_ms = ?, heartbeat_at_ms = ?
             WHERE run_id = ? AND worker_id = ?
            """;

    private static final String RELEASE = """
            DELETE FROM run_lease WHERE run_id = ? AND worker_id = ?
            """;

    private final DataSource dataSource;

    public JdbcRunLeaseStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<RunLease> find(UUID runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT)) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RunLease(
                        UUID.fromString(rs.getString("run_id")),
                        rs.getString("worker_id"),
                        rs.getLong("leased_until_ms"),
                        rs.getLong("claimed_at_ms"),
                        rs.getLong("heartbeat_at_ms")));
            }
        } catch (SQLException e) {
            throw new StepStoreException("find lease failed for run " + runId, e);
        }
    }

    @Override
    public boolean tryAcquire(UUID runId, String workerId, long leasedUntilMs, long nowMs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, runId.toString());
            ps.setString(2, workerId);
            ps.setLong(3, leasedUntilMs);
            ps.setLong(4, nowMs);
            ps.setLong(5, nowMs);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (!isIntegrityViolation(e)) {
                throw new StepStoreException("acquire lease failed for run " + runId, e);
            }
        }

        // A lease exists. Take it over only if it has expired.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(TAKE_OVER)) {
            ps.setString(1, workerId);
            ps.setLong(2, leasedUntilMs);
            ps.setLong(3, nowMs);
            ps.setLong(4, nowMs);
            ps.setString(5, runId.toString());
            ps.setLong(6, nowMs);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StepStoreException("take over lease failed for run " + runId, e);
        }
    }

    @Override
    public boolean heartbeat(UUID runId, String workerId, long leasedUntilMs, long nowMs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(HEARTBEAT)) {
            ps.setLong(1, leasedUntilMs);
            ps.setLong(2, nowMs);
            ps.setString(3, runId.toString());
            ps.setString(4, workerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StepStoreException("heartbeat failed for run " + runId, e);
        }
    }

    @Override
    public boolean release(UUID runId, String workerId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(RELEASE)) {
            ps.setString(1, runId.toString());
            ps.setString(2, workerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StepStoreException("release lease failed for run " + runId, e);
        }
    }

    private static boolean isIntegrityViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
