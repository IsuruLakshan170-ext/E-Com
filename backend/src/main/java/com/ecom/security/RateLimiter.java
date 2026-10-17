package com.ecom.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * In-memory token buckets, keyed by any string (IP, user id, API key id). Limits are per instance;
 * replace with Redis (e.g. Bucket4j) before running more than one replica.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final int MAX_TRACKED_KEYS = 100_000;
    private static final long IDLE_EVICTION_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public Decision tryConsume(String key, int perMinute) {
        if (buckets.size() > MAX_TRACKED_KEYS) {
            long now = System.nanoTime();
            buckets.values().removeIf(b -> b.idleNanos(now) > IDLE_EVICTION_NANOS);
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(perMinute)).tryConsume();
    }

    static final class Bucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefill;

        Bucket(int perMinute) {
            this.capacity = perMinute;
            this.refillPerNano = perMinute / (double) TimeUnit.MINUTES.toNanos(1);
            this.tokens = perMinute;
            this.lastRefill = System.nanoTime();
        }

        synchronized Decision tryConsume() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            long retryAfter = Math.max(1, (long) Math.ceil((1 - tokens) / refillPerNano / TimeUnit.SECONDS.toNanos(1)));
            return new Decision(false, retryAfter);
        }

        synchronized long idleNanos(long now) {
            return now - lastRefill;
        }
    }
}
