package com.ecom.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** H4: buckets are per key, so each API key / user gets its own allowance. */
class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();

    @Test
    void deniesOnceTheBucketIsEmpty() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("key:a", 3).allowed()).isTrue();
        }
        RateLimiter.Decision denied = limiter.tryConsume("key:a", 3);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("key:a", 3);
        }
        assertThat(limiter.tryConsume("key:b", 3).allowed()).isTrue();
    }

}
