package dev.jobscheduler.domain;

public enum JobStatus {
    QUEUED,
    SCHEDULED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PAUSED
}
