package dev.jobscheduler.engine;

import dev.jobscheduler.domain.*;
import dev.jobscheduler.persistence.JobRepository;
import dev.jobscheduler.persistence.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final JobRepository jobRepository;
    private final RunRepository runRepository;

    public RecoveryService(JobRepository jobRepository, RunRepository runRepository) {
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
    }

    public void recover() {
        log.info("Starting recovery check...");

        List<Run> staleRuns = runRepository.findByStatus(RunStatus.RUNNING);
        for (Run run : staleRuns) {
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage("Server restart");
            run.setFinishedAt(Instant.now());
            runRepository.update(run);
        }
        if (!staleRuns.isEmpty()) {
            log.info("Recovered {} stale runs", staleRuns.size());
        }

        List<Job> staleJobs = jobRepository.findByStatus(JobStatus.RUNNING);
        for (Job job : staleJobs) {
            job.setStatus(JobStatus.SCHEDULED);
            Instant nextRun = NextRunCalculator.calculate(job);
            job.setNextRunAt(nextRun != null ? nextRun : Instant.now());
            job.setUpdatedAt(Instant.now());
            jobRepository.update(job);
        }
        if (!staleJobs.isEmpty()) {
            log.info("Recovered {} stale jobs -> SCHEDULED", staleJobs.size());
        }

        if (staleRuns.isEmpty() && staleJobs.isEmpty()) {
            log.info("No recovery needed");
        }
    }
}
