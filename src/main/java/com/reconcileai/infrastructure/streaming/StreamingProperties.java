package com.reconcileai.infrastructure.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * SSE limits. Every open stream holds a servlet async context, so the total is capped and each stream expires;
 * clients reconnect (and get a fresh snapshot) or fall back to polling GET /investigations/{id}.
 */
@Validated
@ConfigurationProperties(prefix = "reconcile.streaming")
public record StreamingProperties(
    @Min(1000) long emitterTimeoutMs,
    @Min(1) int maxSubscribers,
    @Min(1) int maxSubscribersPerJob,
    @Min(1000) long heartbeatIntervalMs
) {}
