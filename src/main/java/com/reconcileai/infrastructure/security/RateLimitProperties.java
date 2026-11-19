// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * reconcile.ratelimit.* — per-tenant limit on requests that start paid model calls.
 * Token bucket: {@code capacity} requests can burst, then {@code refillPerMinute} are added back every minute.
 * {@code store}: {@code memory} (default, one bucket per JVM) or {@code redis} (one bucket shared by all replicas).
 */
@ConfigurationProperties("reconcile.ratelimit")
public record RateLimitProperties(
    boolean enabled,
    int capacity,
    int refillPerMinute,
    String store
) {
    public RateLimitProperties {
        store = store == null || store.isBlank() ? "memory" : store.trim().toLowerCase(java.util.Locale.ROOT);
        if (!store.equals("memory") && !store.equals("redis")) {
            throw new IllegalArgumentException("reconcile.ratelimit.store must be memory or redis, was " + store);
        }
    }

    public boolean shared() {
        return "redis".equals(store);
    }
}
