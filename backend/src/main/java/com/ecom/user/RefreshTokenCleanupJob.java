package com.ecom.user;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.common.jobs.JobLocks;

/** M10: expired refresh tokens are useless after a short forensic window. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);
    private static final Duration KEEP_AFTER_EXPIRY = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokens;
    private final JobLocks jobLocks;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokens, JobLocks jobLocks) {
        this.refreshTokens = refreshTokens;
        this.jobLocks = jobLocks;
    }

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void run() {
        if (!jobLocks.tryLock(JobLocks.REFRESH_TOKEN_CLEANUP)) {
            return;
        }
        int deleted = refreshTokens.deleteExpiredBefore(Instant.now().minus(KEEP_AFTER_EXPIRY));
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }
}
