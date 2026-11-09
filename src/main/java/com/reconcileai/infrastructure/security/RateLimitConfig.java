package com.reconcileai.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class RateLimitConfig {

    @Bean
    public TokenBucketRateLimiter investigationRateLimiter(RateLimitProperties props) {
        return new TokenBucketRateLimiter(props.capacity(), props.refillPerMinute(), Clock.systemUTC());
    }
}
