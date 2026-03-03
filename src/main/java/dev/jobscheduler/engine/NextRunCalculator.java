package dev.jobscheduler.engine;

import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.ScheduleType;

import java.time.Instant;

public class NextRunCalculator {

    public static Instant calculate(Job job) {
        return switch (job.getScheduleType()) {
            case ONCE -> calculateOnce(job);
            case FIXED_RATE -> calculateFixedRate(job);
            case FIXED_DELAY -> calculateFixedDelay(job);
        };
    }

    private static Instant calculateOnce(Job job) {
        if (job.getLastFinishAt() != null) {
            return null;
        }
        return job.getStartAt() != null ? job.getStartAt() : Instant.now();
    }

    private static Instant calculateFixedRate(Job job) {
        if (job.getIntervalMs() == null || job.getIntervalMs() <= 0) {
            return null;
        }
        Instant base;
        if (job.getLastRunAt() != null) {
            base = job.getLastRunAt().plusMillis(job.getIntervalMs());
        } else if (job.getStartAt() != null) {
            base = job.getStartAt();
        } else {
            base = Instant.now();
        }

        Instant now = Instant.now();
        if (base.isBefore(now)) {
            long missedMs = now.toEpochMilli() - base.toEpochMilli();
            long skippedIntervals = missedMs / job.getIntervalMs();
            base = base.plusMillis((skippedIntervals + 1) * job.getIntervalMs());
        }
        return base;
    }

    private static Instant calculateFixedDelay(Job job) {
        if (job.getIntervalMs() == null || job.getIntervalMs() <= 0) {
            return null;
        }
        if (job.getLastFinishAt() != null) {
            return job.getLastFinishAt().plusMillis(job.getIntervalMs());
        }
        return job.getStartAt() != null ? job.getStartAt() : Instant.now();
    }
}
