package dev.jobscheduler.persistence;

import dev.jobscheduler.domain.Run;
import dev.jobscheduler.domain.RunStatus;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RunRepository {

    private final DataSource dataSource;

    public RunRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void save(Run run) {
        String sql = """
                INSERT INTO runs (run_id, job_id, started_at, finished_at, status, attempt_number, error_message, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, run.getRunId());
            ps.setString(i++, run.getJobId());
            ps.setTimestamp(i++, Timestamp.from(run.getStartedAt()));
            ps.setTimestamp(i++, run.getFinishedAt() == null ? null : Timestamp.from(run.getFinishedAt()));
            ps.setString(i++, run.getStatus().name());
            ps.setInt(i++, run.getAttemptNumber());
            ps.setString(i++, run.getErrorMessage());
            if (run.getDurationMs() != null) {
                ps.setLong(i++, run.getDurationMs());
            } else {
                ps.setNull(i++, Types.BIGINT);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save run: " + run.getRunId(), e);
        }
    }

    public void update(Run run) {
        String sql = """
                UPDATE runs SET finished_at=?, status=?, error_message=?, duration_ms=?
                WHERE run_id=?
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setTimestamp(i++, run.getFinishedAt() == null ? null : Timestamp.from(run.getFinishedAt()));
            ps.setString(i++, run.getStatus().name());
            ps.setString(i++, run.getErrorMessage());
            if (run.getDurationMs() != null) {
                ps.setLong(i++, run.getDurationMs());
            } else {
                ps.setNull(i++, Types.BIGINT);
            }
            ps.setString(i++, run.getRunId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update run: " + run.getRunId(), e);
        }
    }

    public List<Run> findByJobId(String jobId, int limit) {
        String sql = "SELECT * FROM runs WHERE job_id = ? ORDER BY started_at DESC LIMIT ?";
        List<Run> runs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find runs for job: " + jobId, e);
        }
        return runs;
    }

    public List<Run> findByStatus(RunStatus status) {
        String sql = "SELECT * FROM runs WHERE status = ?";
        List<Run> runs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find runs by status: " + status, e);
        }
        return runs;
    }

    public long countByStatus(RunStatus status) {
        String sql = "SELECT COUNT(*) FROM runs WHERE status = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count runs", e);
        }
    }

    public long countAll() {
        String sql = "SELECT COUNT(*) FROM runs";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count runs", e);
        }
    }

    private Run mapRow(ResultSet rs) throws SQLException {
        Run run = new Run();
        run.setRunId(rs.getString("run_id"));
        run.setJobId(rs.getString("job_id"));
        run.setStartedAt(rs.getTimestamp("started_at").toInstant());
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        run.setFinishedAt(finishedAt == null ? null : finishedAt.toInstant());
        run.setStatus(RunStatus.valueOf(rs.getString("status")));
        run.setAttemptNumber(rs.getInt("attempt_number"));
        run.setErrorMessage(rs.getString("error_message"));
        long durationMs = rs.getLong("duration_ms");
        run.setDurationMs(rs.wasNull() ? null : durationMs);
        return run;
    }
}
