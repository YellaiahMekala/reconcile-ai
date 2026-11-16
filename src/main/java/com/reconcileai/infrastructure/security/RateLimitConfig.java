package com.reconcileai.infrastructure.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

@Configuration
public class RateLimitConfig {

    /**
     * {@code store=memory}: the per-JVM bucket. {@code store=redis}: the shared bucket, with the per-JVM bucket as its
     * fallback while Redis is unreachable. Asking for redis without a Redis connection is a start-up error.
     */
    @Bean
    public RateLimiter investigationRateLimiter(RateLimitProperties props,
                                                ObjectProvider<RedisConnectionFactory> redis,
                                                MeterRegistry meters) {
        TokenBucketRateLimiter local = new TokenBucketRateLimiter(props.capacity(), props.refillPerMinute(), Clock.systemUTC());
        if (!props.shared()) {
            return local;
        }
        RedisConnectionFactory factory = redis.getIfAvailable();
        if (factory == null) {
            throw new IllegalStateException("reconcile.ratelimit.store=redis needs a Redis connection (spring.data.redis.*)");
        }
        return new RedisTokenBucketRateLimiter(new StringRedisTemplate(factory), props.capacity(),
            props.refillPerMinute(), local, meters);
    }
}
