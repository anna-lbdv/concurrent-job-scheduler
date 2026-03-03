package dev.jobscheduler.engine;

import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.JobStatus;
import dev.jobscheduler.domain.Run;
import dev.jobscheduler.domain.RunStatus;
import dev.jobscheduler.metrics.MetricsCollector;
import dev.jobscheduler.persistence.JobRepository;
import dev.jobscheduler.persistence.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimeoutWatcher {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWatcher.class);

    private final RunRepository runRepository;
    private final JobRepository jobRepository;
    private final MetricsCollector metrics;
    private final ScheduledExecutorService scheduler;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public TimeoutWatcher(RunRepository runRepository, JobRepository jobRepository,
            MetricsCollector metrics, long baseBackoffMs, long maxBackoffMs) {
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.metrics = metrics;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "timeout-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(long checkIntervalMs) {
        scheduler.scheduleWithFixedDelay(this::check, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Timeout watcher started, check interval {} ms", checkIntervalMs);
    }

    private void check() {
        try {
            List<Run> runningRuns = runRepository.findByStatus(RunStatus.RUNNING);
            for (Run run : runningRuns) {
                Optional<Job> jobOpt = jobRepository.findById(run.getJobId());
                if (jobOpt.isEmpty())
                    continue;

                Job job = jobOpt.get();
                long elapsed = Duration.between(run.getStartedAt(), Instant.now()).toMillis();
                if (elapsed > job.getTimeoutMs()) {
                    log.warn("Run {} of job {} timed out ({} ms > {} ms)",
                            run.getRunId(), job.getId(), elapsed, job.getTimeoutMs());

                    Instant now = Instant.now();
                    run.setStatus(RunStatus.TIMED_OUT);
                    run.setFinishedAt(now);
                    run.setDurationMs(elapsed);
                    run.setErrorMessage("Timed out after " + elapsed + " ms");
                    runRepository.update(run);

                    metrics.recordTimeout(elapsed);

                    job.setLastFinishAt(now);
                    job.setLastError("Timed out after " + elapsed + " ms");

                    if (job.getAttempts() < job.getMaxRetries()) {
                        long backoff = BackoffCalculator.calculate(job.getAttempts(), baseBackoffMs, maxBackoffMs);
                        job.setStatus(JobStatus.SCHEDULED);
                        job.setNextRunAt(now.plusMillis(backoff));
                    } else {
                        job.setStatus(JobStatus.FAILED);
                    }
                    job.setUpdatedAt(now);
                    jobRepository.update(job);
                }
            }
        } catch (Exception e) {
            log.error("Error in timeout watcher", e);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
