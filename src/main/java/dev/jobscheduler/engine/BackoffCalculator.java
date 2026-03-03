package dev.jobscheduler.engine;

public class BackoffCalculator {

    public static long calculate(int attempt, long baseMs, long maxMs) {
        if (attempt <= 0) {
            return baseMs;
        }
        long delay = baseMs * (1L << (attempt - 1));
        if (delay < 0 || delay > maxMs) {
            return maxMs;
        }
        return delay;
    }
}
