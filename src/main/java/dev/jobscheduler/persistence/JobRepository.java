package dev.jobscheduler.persistence;

import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.JobStatus;
import dev.jobscheduler.domain.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JobRepository {

    private static final Logger log = LoggerFactory.getLogger(JobRepository.class);
    private final DataSource dataSource;

    public JobRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void save(Job job) {
        String sql = """
                INSERT INTO jobs (id, name, type, payload, schedule_type, start_at, interval_ms,
                    max_retries, retry_backoff_ms, timeout_ms, status, created_at, updated_at,
                    next_run_at, attempts, last_error, last_run_at, last_finish_at, cancel_requested)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            mapJobToStatement(ps, job);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save job: " + job.getId(), e);
        }
    }

    public void update(Job job) {
        job.setUpdatedAt(Instant.now());
        String sql = """
                UPDATE jobs SET name=?, type=?, payload=?, schedule_type=?, start_at=?, interval_ms=?,
                    max_retries=?, retry_backoff_ms=?, timeout_ms=?, status=?, updated_at=?,
                    next_run_at=?, attempts=?, last_error=?, last_run_at=?, last_finish_at=?, cancel_requested=?
                WHERE id=?
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, job.getName());
            ps.setString(i++, job.getType());
            ps.setString(i++, job.getPayload());
            ps.setString(i++, job.getScheduleType().name());
            ps.setTimestamp(i++, toTimestamp(job.getStartAt()));
            if (job.getIntervalMs() != null) {
                ps.setLong(i++, job.getIntervalMs());
            } else {
                ps.setNull(i++, Types.BIGINT);
            }
            ps.setInt(i++, job.getMaxRetries());
            ps.setLong(i++, job.getRetryBackoffMs());
            ps.setLong(i++, job.getTimeoutMs());
            ps.setString(i++, job.getStatus().name());
            ps.setTimestamp(i++, toTimestamp(job.getUpdatedAt()));
            ps.setTimestamp(i++, toTimestamp(job.getNextRunAt()));
            ps.setInt(i++, job.getAttempts());
            ps.setString(i++, job.getLastError());
            ps.setTimestamp(i++, toTimestamp(job.getLastRunAt()));
            ps.setTimestamp(i++, toTimestamp(job.getLastFinishAt()));
            ps.setBoolean(i++, job.isCancelRequested());
            ps.setString(i++, job.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update job: " + job.getId(), e);
        }
    }

    public Optional<Job> findById(String id) {
        String sql = "SELECT * FROM jobs WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find job: " + id, e);
        }
        return Optional.empty();
    }

    public List<Job> findAll(String statusFilter, String typeFilter, String nameFilter, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM jobs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append(" AND status = ?");
            params.add(statusFilter.toUpperCase());
        }
        if (typeFilter != null && !typeFilter.isBlank()) {
            sql.append(" AND type = ?");
            params.add(typeFilter);
        }
        if (nameFilter != null && !nameFilter.isBlank()) {
            sql.append(" AND LOWER(name) LIKE ?");
            params.add("%" + nameFilter.toLowerCase() + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<Job> jobs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    jobs.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list jobs", e);
        }
        return jobs;
    }

    public List<Job> claimJobsForExecution(int limit) {
        String selectSql = """
                SELECT * FROM jobs
                WHERE status IN ('SCHEDULED', 'QUEUED')
                  AND next_run_at <= ?
                ORDER BY next_run_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;
        String updateSql = "UPDATE jobs SET status = 'RUNNING', updated_at = ? WHERE id = ?";

        List<Job> claimed = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement selectPs = conn.prepareStatement(selectSql)) {
                selectPs.setTimestamp(1, Timestamp.from(Instant.now()));
                selectPs.setInt(2, limit);
                try (ResultSet rs = selectPs.executeQuery()) {
                    while (rs.next()) {
                        claimed.add(mapRow(rs));
                    }
                }
            }

            if (!claimed.isEmpty()) {
                try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                    Timestamp now = Timestamp.from(Instant.now());
                    for (Job job : claimed) {
                        updatePs.setTimestamp(1, now);
                        updatePs.setString(2, job.getId());
                        updatePs.addBatch();
                        job.setStatus(JobStatus.RUNNING);
                        job.setUpdatedAt(now.toInstant());
                    }
                    updatePs.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim jobs", e);
        }
        return claimed;
    }

    public List<Job> findByStatus(JobStatus status) {
        String sql = "SELECT * FROM jobs WHERE status = ?";
        List<Job> jobs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    jobs.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find jobs by status: " + status, e);
        }
        return jobs;
    }

    public boolean delete(String id) {
        String sql = "DELETE FROM jobs WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete job: " + id, e);
        }
    }

    private void mapJobToStatement(PreparedStatement ps, Job job) throws SQLException {
        int i = 1;
        ps.setString(i++, job.getId());
        ps.setString(i++, job.getName());
        ps.setString(i++, job.getType());
        ps.setString(i++, job.getPayload());
        ps.setString(i++, job.getScheduleType().name());
        ps.setTimestamp(i++, toTimestamp(job.getStartAt()));
        if (job.getIntervalMs() != null) {
            ps.setLong(i++, job.getIntervalMs());
        } else {
            ps.setNull(i++, Types.BIGINT);
        }
        ps.setInt(i++, job.getMaxRetries());
        ps.setLong(i++, job.getRetryBackoffMs());
        ps.setLong(i++, job.getTimeoutMs());
        ps.setString(i++, job.getStatus().name());
        ps.setTimestamp(i++, toTimestamp(job.getCreatedAt()));
        ps.setTimestamp(i++, toTimestamp(job.getUpdatedAt()));
        ps.setTimestamp(i++, toTimestamp(job.getNextRunAt()));
        ps.setInt(i++, job.getAttempts());
        ps.setString(i++, job.getLastError());
        ps.setTimestamp(i++, toTimestamp(job.getLastRunAt()));
        ps.setTimestamp(i++, toTimestamp(job.getLastFinishAt()));
        ps.setBoolean(i++, job.isCancelRequested());
    }

    private Job mapRow(ResultSet rs) throws SQLException {
        Job job = new Job();
        job.setId(rs.getString("id"));
        job.setName(rs.getString("name"));
        job.setType(rs.getString("type"));
        job.setPayload(rs.getString("payload"));
        job.setScheduleType(ScheduleType.valueOf(rs.getString("schedule_type")));
        job.setStartAt(toInstant(rs.getTimestamp("start_at")));
        long intervalMs = rs.getLong("interval_ms");
        job.setIntervalMs(rs.wasNull() ? null : intervalMs);
        job.setMaxRetries(rs.getInt("max_retries"));
        job.setRetryBackoffMs(rs.getLong("retry_backoff_ms"));
        job.setTimeoutMs(rs.getLong("timeout_ms"));
        job.setStatus(JobStatus.valueOf(rs.getString("status")));
        job.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        job.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        job.setNextRunAt(toInstant(rs.getTimestamp("next_run_at")));
        job.setAttempts(rs.getInt("attempts"));
        job.setLastError(rs.getString("last_error"));
        job.setLastRunAt(toInstant(rs.getTimestamp("last_run_at")));
        job.setLastFinishAt(toInstant(rs.getTimestamp("last_finish_at")));
        job.setCancelRequested(rs.getBoolean("cancel_requested"));
        return job;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
