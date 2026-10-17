package com.ecom.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** H5 per (account, IP) throttle and R5 per-account ceiling across IPs. */
class LoginThrottleTest {

    private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), 5, Duration.ofHours(1));
    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");

    @Test
    void blocksThePairAfterMaxFailures() {
        assertThat(throttle.recordFailure("a@x.com", "1.1.1.1", now)).isEqualTo(LoginThrottle.Outcome.NONE);
        assertThat(throttle.recordFailure("a@x.com", "1.1.1.1", now)).isEqualTo(LoginThrottle.Outcome.NONE);
        assertThat(throttle.recordFailure("a@x.com", "1.1.1.1", now)).isEqualTo(LoginThrottle.Outcome.PAIR_BLOCKED);
        assertThat(throttle.isPairBlocked("a@x.com", "1.1.1.1", now)).isTrue();
    }

    @Test
    void otherIpsAreNotAffectedByAPairBlock() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("a@x.com", "6.6.6.6", now);
        }
        assertThat(throttle.shouldReject("a@x.com", "1.1.1.1", now)).isFalse();
    }

    @Test
    void pairBlockExpiresAfterTheWindow() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("a@x.com", "1.1.1.1", now);
        }
        assertThat(throttle.isPairBlocked("a@x.com", "1.1.1.1", now.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void successClearsPairFailures() {
        throttle.recordFailure("a@x.com", "1.1.1.1", now);
        throttle.recordFailure("a@x.com", "1.1.1.1", now);
        throttle.recordSuccess("a@x.com", "1.1.1.1", now);
        assertThat(throttle.recordFailure("a@x.com", "1.1.1.1", now)).isEqualTo(LoginThrottle.Outcome.NONE);
    }

    @Test
    void manyIpsHittingOneAccountReachTheCeiling() {
        for (int i = 1; i < 5; i++) {
            assertThat(throttle.recordFailure("a@x.com", "10.0.0." + i, now)).isEqualTo(LoginThrottle.Outcome.NONE);
        }
        assertThat(throttle.recordFailure("a@x.com", "10.0.0.5", now)).isEqualTo(LoginThrottle.Outcome.ACCOUNT_CEILING_REACHED);
        assertThat(throttle.requiresStepUp("a@x.com", "10.0.0.99", now)).isTrue();
        assertThat(throttle.shouldReject("a@x.com", "10.0.0.99", now)).isTrue();
    }

    @Test
    void aTrustedIpStillWorksDuringTheAttack() {
        throttle.recordSuccess("a@x.com", "192.168.1.10", now); // the real user's usual IP
        for (int i = 1; i <= 5; i++) {
            throttle.recordFailure("a@x.com", "10.0.0." + i, now);
        }
        assertThat(throttle.requiresStepUp("a@x.com", "192.168.1.10", now)).isFalse();
        assertThat(throttle.requiresStepUp("a@x.com", "10.9.9.9", now)).isTrue();
    }

    @Test
    void ceilingLiftsAfterTheAccountWindow() {
        for (int i = 1; i <= 5; i++) {
            throttle.recordFailure("a@x.com", "10.0.0." + i, now);
        }
        assertThat(throttle.requiresStepUp("a@x.com", "10.9.9.9", now.plus(Duration.ofMinutes(61)))).isFalse();
    }

    @Test
    void unknownAccountsBehaveExactlyTheSame() {
        for (int i = 1; i <= 5; i++) {
            throttle.recordFailure("nobody@x.com", "10.0.0." + i, now);
        }
        assertThat(throttle.requiresStepUp("nobody@x.com", "10.9.9.9", now)).isTrue();
    }

    @Test
    void accountCeilingCanBeDisabled() {
        LoginThrottle noCeiling = new LoginThrottle(3, Duration.ofMinutes(15), 0, Duration.ofHours(1));
        for (int i = 1; i <= 50; i++) {
            noCeiling.recordFailure("a@x.com", "10.0.0." + i, now);
        }
        assertThat(noCeiling.requiresStepUp("a@x.com", "10.9.9.9", now)).isFalse();
    }
}
