package com.reconcileai.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * reconcile.ratelimit.* — per-tenant limit on requests that start paid model calls.
 * Token bucket: {@code capacity} requests can burst, then {@code refillPerMinute} are added back every minute.
 */
@ConfigurationProperties("reconcile.ratelimit")
public record RateLimitProperties(
    boolean enabled,
    int capacity,
    int refillPerMinute
) {}
