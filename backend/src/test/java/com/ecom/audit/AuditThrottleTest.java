package com.ecom.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

/** R8: one audit row per bucket per minute, carrying the number of events it stands for. */
class AuditThrottleTest {

    private final long[] clock = {0};
    private final AuditThrottle throttle = new AuditThrottle(() -> clock[0], Duration.ofMinutes(1));

    @Test
    void firstEventIsWrittenAndTheRestOfTheMinuteIsCounted() {
        assertThat(throttle.tryAcquire("k")).isEqualTo(OptionalLong.of(1));
        assertThat(throttle.tryAcquire("k")).isEmpty();
        assertThat(throttle.tryAcquire("k")).isEmpty();
        assertThat(throttle.tryAcquire("k")).isEmpty();
        clock[0] += Duration.ofSeconds(61).toNanos();
        assertThat(throttle.tryAcquire("k")).isEqualTo(OptionalLong.of(4)); // 3 suppressed + this one
    }

    @Test
    void bucketsAreIndependent() {
        throttle.tryAcquire("a");
        assertThat(throttle.tryAcquire("b")).isEqualTo(OptionalLong.of(1));
    }
}
