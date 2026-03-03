package dev.jobscheduler.engine;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    @Test
    void shouldReturnBaseMsForAttemptZero() {
        assertThat(BackoffCalculator.calculate(0, 1000, 60000)).isEqualTo(1000);
    }

    @Test
    void shouldCalculateExponentialBackoff() {
        assertThat(BackoffCalculator.calculate(1, 1000, 60000)).isEqualTo(1000);
        assertThat(BackoffCalculator.calculate(2, 1000, 60000)).isEqualTo(2000);
        assertThat(BackoffCalculator.calculate(3, 1000, 60000)).isEqualTo(4000);
        assertThat(BackoffCalculator.calculate(4, 1000, 60000)).isEqualTo(8000);
    }

    @Test
    void shouldCapAtMaxMs() {
        assertThat(BackoffCalculator.calculate(7, 1000, 60000)).isEqualTo(60000);
        assertThat(BackoffCalculator.calculate(20, 1000, 60000)).isEqualTo(60000);
    }
}
