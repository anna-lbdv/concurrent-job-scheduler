package dev.jobscheduler.engine;

import dev.jobscheduler.domain.Job;
import dev.jobscheduler.domain.ScheduleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NextRunCalculatorTest {

    @Test
    void once_shouldReturnStartAtIfSet() {
        Job job = new Job();
        job.setScheduleType(ScheduleType.ONCE);
        Instant startAt = Instant.now().plusSeconds(60);
        job.setStartAt(startAt);

        Instant nextRun = NextRunCalculator.calculate(job);
        assertThat(nextRun).isEqualTo(startAt);
    }

    @Test
    void once_shouldReturnNullIfAlreadyFinished() {
        Job job = new Job();
        job.setScheduleType(ScheduleType.ONCE);
        job.setLastFinishAt(Instant.now());

        Instant nextRun = NextRunCalculator.calculate(job);
        assertThat(nextRun).isNull();
    }

    @Test
    void fixedDelay_shouldReturnLastFinishPlusInterval() {
        Job job = new Job();
        job.setScheduleType(ScheduleType.FIXED_DELAY);
        job.setIntervalMs(5000L);
        Instant finishedAt = Instant.now();
        job.setLastFinishAt(finishedAt);

        Instant nextRun = NextRunCalculator.calculate(job);
        assertThat(nextRun).isEqualTo(finishedAt.plusMillis(5000));
    }

    @Test
    void fixedRate_shouldSkipMissedRuns() {
        Job job = new Job();
        job.setScheduleType(ScheduleType.FIXED_RATE);
        job.setIntervalMs(1000L);

        Instant lastRun = Instant.now().minusMillis(5500);
        job.setLastRunAt(lastRun);

        Instant nextRun = NextRunCalculator.calculate(job);

        long diff = nextRun.toEpochMilli() - lastRun.toEpochMilli();
        assertThat(diff).isEqualTo(6000L);
    }
}
