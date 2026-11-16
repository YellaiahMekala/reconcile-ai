package com.reconcileai.infrastructure.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Token bucket shared by every replica: one Redis hash per tenant, updated by a single Lua script, so the
 * read-refill-take step is atomic across JVMs and N replicas give a tenant the configured rate, not N times it.
 * <p>
 * The script uses the Redis server clock ({@code TIME}), so replicas with skewed clocks still agree. Each key expires
 * once its bucket would be full again, so idle tenants leave nothing behind.
 * <p>
 * If Redis cannot be reached the request is decided by a local per-JVM bucket instead (fail open to the old
 * per-replica limit, not to "unlimited", and not to "refuse everything"), and
 * {@code reconcile.ratelimit.redis.fallback} is counted so the degradation is visible.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    static final String KEY_PREFIX = "reconcile:ratelimit:investigations:";

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private static final String SCRIPT = """
        local capacity = tonumber(ARGV[1])
        local refill_per_ms = tonumber(ARGV[2])
        local ttl_ms = tonumber(ARGV[3])
        local t = redis.call('TIME')
        local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
        local tokens = tonumber(state[1])
        local ts = tonumber(state[2])
        if tokens == nil or ts == nil then
          tokens = capacity
          ts = now
        end
        tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill_per_ms)
        local allowed = 0
        local wait = 0
        if tokens >= 1 then
          tokens = tokens - 1
          allowed = 1
        else
          wait = math.ceil((1 - tokens) / refill_per_ms / 1000)
        end
        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
        redis.call('PEXPIRE', KEYS[1], ttl_ms)
        return {allowed, wait}
        """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> BUCKET = new DefaultRedisScript<>(SCRIPT, List.class);

    private final StringRedisTemplate redis;
    private final RateLimiter fallback;
    private final Counter fallbacks;
    private final String capacity;
    private final String refillPerMs;
    private final String ttlMs;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, int capacity, int refillPerMinute,
                                       RateLimiter fallback, MeterRegistry meters) {
        if (capacity < 1 || refillPerMinute < 1) {
            throw new IllegalArgumentException("capacity and refillPerMinute must be at least 1");
        }
        this.redis = redis;
        this.fallback = fallback;
        this.fallbacks = Counter.builder("reconcile.ratelimit.redis.fallback")
            .description("Rate-limit decisions made by the local bucket because Redis was unreachable")
            .register(meters);
        double perMs = refillPerMinute / 60_000.0;
        this.capacity = Integer.toString(capacity);
        this.refillPerMs = Double.toString(perMs);
        this.ttlMs = Long.toString((long) Math.ceil(capacity / perMs) + 1_000L);
    }

    @Override
    public Decision tryAcquire(String key) {
        try {
            List<?> result = redis.execute(BUCKET, List.of(KEY_PREFIX + key), capacity, refillPerMs, ttlMs);
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("unexpected rate-limit script reply");
            }
            boolean allowed = ((Number) result.get(0)).longValue() == 1L;
            long wait = ((Number) result.get(1)).longValue();
            return new Decision(allowed, allowed ? 0 : Math.max(1, wait));
        } catch (RuntimeException e) {
            fallbacks.increment();
            log.warn("Shared rate limiter unavailable ({}); deciding with the local bucket", e.getClass().getSimpleName());
            return fallback.tryAcquire(key);
        }
    }
}
