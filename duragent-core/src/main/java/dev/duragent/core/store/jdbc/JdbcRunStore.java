package dev.duragent.core.store.jdbc;

import dev.duragent.core.model.Checkpoint;
import dev.duragent.core.model.Run;
import dev.duragent.core.model.RunStatus;
import dev.duragent.core.store.RunStore;
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

/**
 * Plain JDBC implementation of {@link RunStore}.
 *
 * <p>Reads and writes never span a side effect, same as {@link JdbcStepStore}: a connection is
 * borrowed per statement and returned immediately.
 */
public final class JdbcRunStore implements RunStore {

    private static final String INSERT_RUN = """
            INSERT INTO run (id, workflow, status, input_json, state_json, cursor_node, error_text,
                             attempt, version, created_at_ms, updated_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RUN = """
            SELECT id, workflow, status, input_json, state_json, cursor_node, error_text,
                   attempt, version, created_at_ms, updated_at_ms
              FROM run
             WHERE id = ?
            """;

    private static final String UPDATE_RUN = """
            UPDATE run
               SET status = ?, state_json = ?, cursor_node = ?, error_text = ?,
                   attempt = ?, version = ?, updated_at_ms = ?
             WHERE id = ? AND version = ?
            """;

    private static final String INSERT_CHECKPOINT = """
            INSERT INTO run_checkpoint (run_id, seq, node, state_json, created_at_ms)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT_LATEST_CHECKPOINT = """
            SELECT run_id, seq, node, state_json, created_at_ms
              FROM run_checkpoint
             WHERE run_id = ?
             ORDER BY seq DESC
             LIMIT 1
            """;

    private static final String SELECT_CHECKPOINTS = """
            SELECT run_id, seq, node, state_json, created_at_ms
              FROM run_checkpoint
             WHERE run_id = ?
             ORDER BY seq ASC
            """;

    private final DataSource dataSource;

    public JdbcRunStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void create(Run run) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_RUN)) {
            ps.setString(1, run.id().toString());
            ps.setString(2, run.workflow());
            ps.setString(3, run.status().name());
            ps.setString(4, run.inputJson());
            ps.setString(5, run.stateJson());
            ps.setString(6, run.cursor());
            ps.setString(7, run.error());
            ps.setInt(8, run.attempt());
            ps.setInt(9, run.version());
            ps.setLong(10, run.createdAtMs());
            ps.setLong(11, run.updatedAtMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StepStoreException("create run failed for " + run.id(), e);
        }
    }

    @Override
    public Optional<Run> find(UUID runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_RUN)) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StepStoreException("find run failed for " + runId, e);
        }
    }

    @Override
    public boolean update(Run run, int expectedVersion) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_RUN)) {
            ps.setString(1, run.status().name());
            ps.setString(2, run.stateJson());
            ps.setString(3, run.cursor());
            ps.setString(4, run.error());
            ps.setInt(5, run.attempt());
            ps.setInt(6, run.version());
            ps.setLong(7, run.updatedAtMs());
            ps.setString(8, run.id().toString());
            ps.setInt(9, expectedVersion);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StepStoreException("update run failed for " + run.id(), e);
        }
    }

    @Override
    public void appendCheckpoint(Checkpoint checkpoint) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_CHECKPOINT)) {
            ps.setString(1, checkpoint.runId().toString());
            ps.setInt(2, checkpoint.seq());
            ps.setString(3, checkpoint.node());
            ps.setString(4, checkpoint.stateJson());
            ps.setLong(5, checkpoint.createdAtMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StepStoreException("append checkpoint failed for " + checkpoint.runId()
                    + " seq " + checkpoint.seq(), e);
        }
    }

    @Override
    public Optional<Checkpoint> latestCheckpoint(UUID runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_LATEST_CHECKPOINT)) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCheckpoint(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StepStoreException("latest checkpoint failed for " + runId, e);
        }
    }

    @Override
    public List<Checkpoint> checkpoints(UUID runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_CHECKPOINTS)) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<Checkpoint> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapCheckpoint(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new StepStoreException("checkpoint list failed for " + runId, e);
        }
    }

    private static Run map(ResultSet rs) throws SQLException {
        return new Run(
                UUID.fromString(rs.getString("id")),
                rs.getString("workflow"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getString("input_json"),
                rs.getString("state_json"),
                rs.getString("cursor_node"),
                rs.getString("error_text"),
                rs.getInt("attempt"),
                rs.getInt("version"),
                rs.getLong("created_at_ms"),
                rs.getLong("updated_at_ms"));
    }

    private static Checkpoint mapCheckpoint(ResultSet rs) throws SQLException {
        return new Checkpoint(
                UUID.fromString(rs.getString("run_id")),
                rs.getInt("seq"),
                rs.getString("node"),
                rs.getString("state_json"),
                rs.getLong("created_at_ms"));
    }
}
