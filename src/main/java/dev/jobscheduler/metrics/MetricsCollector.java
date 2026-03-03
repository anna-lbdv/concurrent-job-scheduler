package dev.jobscheduler.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class MetricsCollector {

    private final LongAdder totalRuns = new LongAdder();
    private final LongAdder runsSucceeded = new LongAdder();
    private final LongAdder runsFailed = new LongAdder();
    private final LongAdder runsTimedOut = new LongAdder();
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong durationCount = new AtomicLong(0);
    private volatile int queueSize;
    private volatile int activeWorkers;

    public void recordSuccess(long durationMs) {
        totalRuns.increment();
        runsSucceeded.increment();
        recordDuration(durationMs);
    }

    public void recordFailure(long durationMs) {
        totalRuns.increment();
        runsFailed.increment();
        recordDuration(durationMs);
    }

    public void recordTimeout(long durationMs) {
        totalRuns.increment();
        runsTimedOut.increment();
        recordDuration(durationMs);
    }

    private void recordDuration(long durationMs) {
        totalDurationMs.addAndGet(durationMs);
        durationCount.incrementAndGet();
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public void setActiveWorkers(int activeWorkers) {
        this.activeWorkers = activeWorkers;
    }

    public long getTotalRuns() {
        return totalRuns.sum();
    }

    public long getRunsSucceeded() {
        return runsSucceeded.sum();
    }

    public long getRunsFailed() {
        return runsFailed.sum();
    }

    public long getRunsTimedOut() {
        return runsTimedOut.sum();
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getActiveWorkers() {
        return activeWorkers;
    }

    public long getAvgDurationMs() {
        long count = durationCount.get();
        return count == 0 ? 0 : totalDurationMs.get() / count;
    }

    public String toJson() {
        return """
                {
                  "total_runs": %d,
                  "runs_succeeded": %d,
                  "runs_failed": %d,
                  "runs_timed_out": %d,
                  "queue_size": %d,
                  "active_workers": %d,
                  "avg_duration_ms": %d
                }
                """.formatted(
                getTotalRuns(), getRunsSucceeded(), getRunsFailed(),
                getRunsTimedOut(), getQueueSize(), getActiveWorkers(),
                getAvgDurationMs());
    }
}
