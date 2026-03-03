CREATE TABLE jobs (
    id              VARCHAR(36)  PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(100) NOT NULL,
    payload         CLOB,
    schedule_type   VARCHAR(20)  NOT NULL,
    start_at        TIMESTAMP,
    interval_ms     BIGINT,
    max_retries     INT          NOT NULL DEFAULT 0,
    retry_backoff_ms BIGINT      NOT NULL DEFAULT 1000,
    timeout_ms      BIGINT       NOT NULL DEFAULT 30000,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    next_run_at     TIMESTAMP,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      CLOB,
    last_run_at     TIMESTAMP,
    last_finish_at  TIMESTAMP,
    cancel_requested BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE runs (
    run_id          VARCHAR(36)  PRIMARY KEY,
    job_id          VARCHAR(36)  NOT NULL,
    started_at      TIMESTAMP    NOT NULL,
    finished_at     TIMESTAMP,
    status          VARCHAR(20)  NOT NULL,
    attempt_number  INT          NOT NULL,
    error_message   CLOB,
    duration_ms     BIGINT,
    FOREIGN KEY (job_id) REFERENCES jobs(id)
);

CREATE INDEX idx_jobs_status_next_run ON jobs(status, next_run_at);
CREATE INDEX idx_runs_job_id ON runs(job_id);
