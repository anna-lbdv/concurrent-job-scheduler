package dev.jobscheduler.domain;

import java.time.Instant;
import java.util.UUID;

public class Run {

    private String runId;
    private String jobId;
    private Instant startedAt;
    private Instant finishedAt;
    private RunStatus status;
    private int attemptNumber;
    private String errorMessage;
    private Long durationMs;

    public Run() {
        this.runId = UUID.randomUUID().toString();
    }

    public Run(String jobId, int attemptNumber) {
        this.runId = UUID.randomUUID().toString();
        this.jobId = jobId;
        this.attemptNumber = attemptNumber;
        this.startedAt = Instant.now();
        this.status = RunStatus.RUNNING;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
