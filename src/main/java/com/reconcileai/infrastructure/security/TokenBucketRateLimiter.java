package com.reconcileai.infrastructure.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory token bucket, one bucket per key (the tenant id).
 *
 * <p>Each bucket holds up to {@code capacity} tokens and gains {@code refillPerMinute} tokens a minute, so a tenant can
 * burst briefly but cannot sustain more than the refill rate. A request that finds the bucket empty is told how many
 * seconds to wait.
 *
 * <p>Limit: state is per JVM. With N replicas a tenant can get up to N times the configured rate. A shared limiter
 * (Redis) is the upgrade if that ever matters; the call site would not change.
 */
public class TokenBucketRateLimiter {

    /** Result of {@link #tryAcquire}: allowed, or how long the caller should wait. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private final int capacity;
    private final double refillPerNano;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, int refillPerMinute, Clock clock) {
        if (capacity < 1 || refillPerMinute < 1) {
            throw new IllegalArgumentException("capacity and refillPerMinute must be at least 1");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerMinute / 60_000_000_000.0;
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        long now = nowNanos();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (now - b.lastRefillNanos) * refillPerNano);
            b.lastRefillNanos = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return new Decision(true, 0);
            }
            double missing = 1 - b.tokens;
            return new Decision(false, (long) Math.ceil(missing / refillPerNano / 1_000_000_000.0));
        }
    }

    private long nowNanos() {
        return clock.millis() * 1_000_000L;
    }
}
