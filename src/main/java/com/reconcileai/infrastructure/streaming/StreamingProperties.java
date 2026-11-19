// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
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
    @Min(1000) long heartbeatIntervalMs,
    /** local (single node) | redis (publish every event on a Redis channel; every node delivers to its own streams) */
    String fanout
) {
    public boolean redisFanout() {
        return "redis".equalsIgnoreCase(fanout);
    }
}
