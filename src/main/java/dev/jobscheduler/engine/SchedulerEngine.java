package dev.jobscheduler.engine;

import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.JobStatus;
import dev.jobscheduler.persistence.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SchedulerEngine {

    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);

    private final JobRepository jobRepository;
    private final WorkerPool workerPool;
    private final ScheduledExecutorService scheduler;
    private final long pollIntervalMs;
    private final int maxConcurrentRuns;
    private volatile boolean running;

    public SchedulerEngine(JobRepository jobRepository, WorkerPool workerPool,
            long pollIntervalMs, int maxConcurrentRuns) {
        this.jobRepository = jobRepository;
        this.workerPool = workerPool;
        this.pollIntervalMs = pollIntervalMs;
        this.maxConcurrentRuns = maxConcurrentRuns;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-poll");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running = true;
        scheduler.scheduleWithFixedDelay(this::poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Scheduler started, poll interval {} ms, max concurrent {}", pollIntervalMs, maxConcurrentRuns);
    }

    private void poll() {
        if (!running)
            return;
        try {
            int available = maxConcurrentRuns - workerPool.getActiveCount();
            if (available <= 0) {
                log.debug("All worker slots occupied, skipping poll");
                return;
            }

            List<Job> claimed = jobRepository.claimJobsForExecution(available);
            if (!claimed.isEmpty()) {
                log.debug("Claimed {} jobs for execution", claimed.size());
            }

            for (Job job : claimed) {
                if (job.getStatus() == JobStatus.PAUSED || job.isCancelRequested()) {
                    revertClaimedJob(job);
                    continue;
                }
                boolean submitted = workerPool.submit(job);
                if (!submitted) {
                    log.warn("Queue full, reverting job {} to SCHEDULED", job.getId());
                    revertClaimedJob(job);
                }
            }
        } catch (Exception e) {
            log.error("Error during poll cycle", e);
        }
    }

    private void revertClaimedJob(Job job) {
        if (job.isCancelRequested()) {
            job.setStatus(JobStatus.CANCELLED);
            job.setCancelRequested(false);
        } else {
            job.setStatus(JobStatus.SCHEDULED);
            Instant nextRun = NextRunCalculator.calculate(job);
            if (nextRun != null) {
                job.setNextRunAt(nextRun);
            }
        }
        job.setUpdatedAt(Instant.now());
        jobRepository.update(job);
    }

    public void shutdown() {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Scheduler stopped");
    }
}
