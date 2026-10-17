package com.ecom.common.idempotency;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.common.jobs.JobLocks;

/** M10: idempotency keys only need to outlive client retries; keep 30 days. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private static final Duration RETENTION = Duration.ofDays(30);

    private final IdempotencyRecordRepository records;
    private final JobLocks jobLocks;

    public IdempotencyCleanupJob(IdempotencyRecordRepository records, JobLocks jobLocks) {
        this.records = records;
        this.jobLocks = jobLocks;
    }

    @Scheduled(cron = "0 15 * * * *")
    @Transactional
    public void run() {
        if (!jobLocks.tryLock(JobLocks.IDEMPOTENCY_CLEANUP)) {
            return;
        }
        int deleted = records.deleteCreatedBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency records", deleted);
        }
    }
}
