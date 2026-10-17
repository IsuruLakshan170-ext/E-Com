package com.ecom.audit;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * R8: lets at most one audit row per bucket per window through and counts the rest, so a flood of
 * denials or bad keys can't flood the (immutable, never-deleted) audit table. In-memory per instance.
 */
final class AuditThrottle {

    private static final int MAX_BUCKETS = 50_000;

    private static final class Bucket {
        long lastWrite;
        long suppressed;

        Bucket(long lastWrite) {
            this.lastWrite = lastWrite;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final long windowNanos;

    AuditThrottle(LongSupplier nanoClock, Duration window) {
        this.nanoClock = nanoClock;
        this.windowNanos = window.toNanos();
    }

    /** @return the number of events the row to write stands for, or empty if this event is suppressed. */
    OptionalLong tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        if (buckets.size() > MAX_BUCKETS) {
            buckets.entrySet().removeIf(e -> now - e.getValue().lastWrite > 10 * windowNanos);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(now - windowNanos)); // first event writes
        synchronized (bucket) {
            if (now - bucket.lastWrite < windowNanos) {
                bucket.suppressed++;
                return OptionalLong.empty();
            }
            long occurrences = bucket.suppressed + 1;
            bucket.suppressed = 0;
            bucket.lastWrite = now;
            return OptionalLong.of(occurrences);
        }
    }
}
