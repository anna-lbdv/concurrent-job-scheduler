package dev.jobscheduler.engine;

import dev.jobscheduler.domain.*;
import dev.jobscheduler.handler.JobHandler;
import dev.jobscheduler.metrics.MetricsCollector;
import dev.jobscheduler.persistence.JobRepository;
import dev.jobscheduler.persistence.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final ThreadPoolExecutor executor;
    private final JobRepository jobRepository;
    private final RunRepository runRepository;
    private final List<JobHandler> handlers;
    private final MetricsCollector metrics;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public WorkerPool(int threads, int queueCapacity, JobRepository jobRepository,
            RunRepository runRepository, List<JobHandler> handlers,
            MetricsCollector metrics, long baseBackoffMs, long maxBackoffMs) {
        this.executor = new ThreadPoolExecutor(
                threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadFactory() {
                    private int counter = 0;

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "worker-" + counter++);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.handlers = handlers;
        this.metrics = metrics;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    public boolean submit(Job job) {
        Run run = new Run(job.getId(), job.getAttempts() + 1);
        try {
            runRepository.save(run);
            executor.execute(() -> executeRun(job, run));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Worker pool queue full, rejecting job {}", job.getId());
            return false;
        }
    }

    private void executeRun(Job job, Run run) {
        MDC.put("jobId", job.getId());
        MDC.put("runId", run.getRunId());
        try {
            log.info("Starting execution, type={}, attempt={}", job.getType(), run.getAttemptNumber());
            job.setLastRunAt(Instant.now());
            job.setAttempts(run.getAttemptNumber());
            jobRepository.update(job);

            metrics.setActiveWorkers(executor.getActiveCount());
            metrics.setQueueSize(executor.getQueue().size());

            JobHandler handler = findHandler(job.getType());
            if (handler == null) {
                throw new RuntimeException("No handler for type: " + job.getType());
            }

            if (job.isCancelRequested()) {
                handleCancel(job, run);
                return;
            }

            String result = handler.execute(job.getPayload());
            finishRun(job, run, RunStatus.SUCCEEDED, null);
            log.info("Succeeded, result length={}", result == null ? 0 : result.length());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishRun(job, run, RunStatus.FAILED, "Interrupted: " + e.getMessage());
            log.warn("Interrupted");
        } catch (Exception e) {
            finishRun(job, run, RunStatus.FAILED, truncate(e.getMessage(), 2000));
            log.error("Failed: {}", e.getMessage());
        } finally {
            metrics.setActiveWorkers(executor.getActiveCount());
            metrics.setQueueSize(executor.getQueue().size());
            MDC.clear();
        }
    }

    private void finishRun(Job job, Run run, RunStatus runStatus, String error) {
        Instant finished = Instant.now();
        long duration = Duration.between(run.getStartedAt(), finished).toMillis();

        run.setFinishedAt(finished);
        run.setStatus(runStatus);
        run.setErrorMessage(error);
        run.setDurationMs(duration);
        runRepository.update(run);

        job.setLastFinishAt(finished);
        job.setUpdatedAt(finished);

        switch (runStatus) {
            case SUCCEEDED -> {
                metrics.recordSuccess(duration);
                handleSuccess(job);
            }
            case FAILED, TIMED_OUT -> {
                if (runStatus == RunStatus.TIMED_OUT) {
                    metrics.recordTimeout(duration);
                } else {
                    metrics.recordFailure(duration);
                }
                handleFailure(job, error);
            }
        }

        jobRepository.update(job);
    }

    private void handleSuccess(Job job) {
        if (job.getScheduleType() == ScheduleType.ONCE) {
            job.setStatus(JobStatus.SUCCEEDED);
        } else {
            Instant nextRun = NextRunCalculator.calculate(job);
            if (nextRun != null) {
                job.setStatus(JobStatus.SCHEDULED);
                job.setNextRunAt(nextRun);
                job.setLastError(null);
            } else {
                job.setStatus(JobStatus.SUCCEEDED);
            }
        }
    }

    private void handleFailure(Job job, String error) {
        job.setLastError(error);
        if (job.getAttempts() < job.getMaxRetries()) {
            long backoff = BackoffCalculator.calculate(job.getAttempts(), baseBackoffMs, maxBackoffMs);
            job.setStatus(JobStatus.SCHEDULED);
            job.setNextRunAt(Instant.now().plusMillis(backoff));
            log.info("Scheduling retry in {} ms (attempt {}/{})", backoff, job.getAttempts(), job.getMaxRetries());
        } else if (job.getScheduleType() != ScheduleType.ONCE) {
            Instant nextRun = NextRunCalculator.calculate(job);
            if (nextRun != null) {
                job.setStatus(JobStatus.SCHEDULED);
                job.setNextRunAt(nextRun);
                log.info("Max retries reached, next scheduled run at {}", nextRun);
            } else {
                job.setStatus(JobStatus.FAILED);
            }
        } else {
            job.setStatus(JobStatus.FAILED);
        }
    }

    private void handleCancel(Job job, Run run) {
        log.info("Cancel requested, aborting");
        finishRun(job, run, RunStatus.FAILED, "Cancelled by user");
        job.setStatus(JobStatus.CANCELLED);
        job.setCancelRequested(false);
        jobRepository.update(job);
    }

    private JobHandler findHandler(String type) {
        return handlers.stream()
                .filter(h -> h.supports(type))
                .findFirst()
                .orElse(null);
    }

    public int getActiveCount() {
        return executor.getActiveCount();
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    public void shutdown(long graceMs) {
        log.info("Shutting down worker pool, grace period {} ms", graceMs);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(graceMs, TimeUnit.MILLISECONDS)) {
                log.warn("Force shutting down workers");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private String truncate(String s, int max) {
        if (s == null)
            return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
