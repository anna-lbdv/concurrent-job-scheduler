package dev.jobscheduler.service;

import dev.jobscheduler.domain.*;
import dev.jobscheduler.engine.NextRunCalculator;
import dev.jobscheduler.engine.WorkerPool;
import dev.jobscheduler.persistence.JobRepository;
import dev.jobscheduler.persistence.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final Set<JobStatus> TERMINAL_STATUSES = Set.of(
            JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED);

    private final JobRepository jobRepository;
    private final RunRepository runRepository;
    private final WorkerPool workerPool;
    private final long defaultTimeoutMs;
    private final int defaultMaxRetries;
    private final long defaultBackoffMs;

    public JobService(JobRepository jobRepository, RunRepository runRepository, WorkerPool workerPool,
            long defaultTimeoutMs, int defaultMaxRetries, long defaultBackoffMs) {
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.workerPool = workerPool;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.defaultMaxRetries = defaultMaxRetries;
        this.defaultBackoffMs = defaultBackoffMs;
    }

    public Job createJob(Job job) {
        validate(job);
        applyDefaults(job);
        job.setNextRunAt(NextRunCalculator.calculate(job));
        jobRepository.save(job);
        log.info("Created job {} (type={}, schedule={})", job.getId(), job.getType(), job.getScheduleType());
        return job;
    }

    public Job getJob(String id) {
        return jobRepository.findById(id).orElse(null);
    }

    public List<Run> getJobRuns(String jobId, int limit) {
        return runRepository.findByJobId(jobId, limit);
    }

    public List<Job> listJobs(String status, String type, String name, int limit, int offset) {
        if (limit <= 0)
            limit = 20;
        if (limit > 100)
            limit = 100;
        if (offset < 0)
            offset = 0;
        return jobRepository.findAll(status, type, name, limit, offset);
    }

    public Job pauseJob(String id) {
        Job job = getOrThrow(id);
        if (job.getStatus() == JobStatus.PAUSED)
            return job;
        if (job.getStatus() != JobStatus.SCHEDULED && job.getStatus() != JobStatus.QUEUED) {
            throw new StateConflictException("Cannot pause job in status " + job.getStatus());
        }
        job.setStatus(JobStatus.PAUSED);
        job.setUpdatedAt(Instant.now());
        jobRepository.update(job);
        log.info("Paused job {}", id);
        return job;
    }

    public Job resumeJob(String id) {
        Job job = getOrThrow(id);
        if (job.getStatus() != JobStatus.PAUSED) {
            throw new StateConflictException("Cannot resume job in status " + job.getStatus());
        }
        job.setStatus(JobStatus.SCHEDULED);
        Instant nextRun = NextRunCalculator.calculate(job);
        job.setNextRunAt(nextRun != null ? nextRun : Instant.now());
        job.setUpdatedAt(Instant.now());
        jobRepository.update(job);
        log.info("Resumed job {}", id);
        return job;
    }

    public Job cancelJob(String id) {
        Job job = getOrThrow(id);
        if (job.getStatus() == JobStatus.CANCELLED)
            return job;
        if (job.getStatus() == JobStatus.RUNNING) {
            job.setCancelRequested(true);
            job.setUpdatedAt(Instant.now());
            jobRepository.update(job);
            log.info("Cancel requested for running job {}", id);
            return job;
        }
        job.setStatus(JobStatus.CANCELLED);
        job.setUpdatedAt(Instant.now());
        jobRepository.update(job);
        log.info("Cancelled job {}", id);
        return job;
    }

    public Run runNow(String id) {
        Job job = getOrThrow(id);
        if (TERMINAL_STATUSES.contains(job.getStatus()) && job.getScheduleType() == ScheduleType.ONCE) {
            throw new StateConflictException("Cannot run-now a completed one-time job");
        }

        job.setStatus(JobStatus.RUNNING);
        job.setUpdatedAt(Instant.now());
        jobRepository.update(job);

        boolean submitted = workerPool.submit(job);
        if (!submitted) {
            job.setStatus(JobStatus.SCHEDULED);
            job.setNextRunAt(Instant.now());
            jobRepository.update(job);
            throw new QueueFullException("Worker pool queue full");
        }

        List<Run> runs = runRepository.findByJobId(id, 1);
        log.info("Run-now triggered for job {}", id);
        return runs.isEmpty() ? null : runs.get(0);
    }

    public boolean deleteJob(String id) {
        Job job = getOrThrow(id);
        if (!TERMINAL_STATUSES.contains(job.getStatus())) {
            throw new StateConflictException(
                    "Cannot delete job in status " + job.getStatus() + ", must be cancelled/failed/succeeded");
        }
        jobRepository.delete(id);
        log.info("Deleted job {}", id);
        return true;
    }

    private Job getOrThrow(String id) {
        Job job = jobRepository.findById(id).orElse(null);
        if (job == null) {
            throw new NotFoundException("Job not found: " + id);
        }
        return job;
    }

    private void validate(Job job) {
        if (job.getName() == null || job.getName().isBlank()) {
            throw new ValidationException("name is required");
        }
        if (job.getType() == null || job.getType().isBlank()) {
            throw new ValidationException("type is required");
        }
        if (job.getScheduleType() == null) {
            throw new ValidationException("scheduleType is required");
        }
        if (job.getScheduleType() != ScheduleType.ONCE) {
            if (job.getIntervalMs() == null || job.getIntervalMs() <= 0) {
                throw new ValidationException("intervalMs is required for periodic jobs");
            }
        }
    }

    private void applyDefaults(Job job) {
        if (job.getTimeoutMs() <= 0)
            job.setTimeoutMs(defaultTimeoutMs);
        if (job.getMaxRetries() <= 0)
            job.setMaxRetries(defaultMaxRetries);
        if (job.getRetryBackoffMs() <= 0)
            job.setRetryBackoffMs(defaultBackoffMs);
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String msg) {
            super(msg);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) {
            super(msg);
        }
    }

    public static class StateConflictException extends RuntimeException {
        public StateConflictException(String msg) {
            super(msg);
        }
    }

    public static class QueueFullException extends RuntimeException {
        public QueueFullException(String msg) {
            super(msg);
        }
    }
}
