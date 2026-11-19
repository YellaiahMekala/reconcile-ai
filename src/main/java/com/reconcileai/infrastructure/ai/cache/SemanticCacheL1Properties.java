// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * reconcile.ai.cache.l1.* — D1.2.1 in-heap Caffeine tier in front of the Redis cache. Only used when the Redis cache
 * itself is on ({@code reconcile.ai.cache.enabled=true}).
 *
 * @param enabled    off by default
 * @param maxEntries per JVM, all tenants together (1 to 10000); default 500
 * @param ttl        per entry, capped at the L2 TTL; default 5 minutes
 */
@ConfigurationProperties("reconcile.ai.cache.l1")
public record SemanticCacheL1Properties(boolean enabled, Integer maxEntries, Duration ttl) {

    public SemanticCacheL1Properties {
        maxEntries = maxEntries == null ? 500 : maxEntries;
        ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        if (maxEntries < 1 || maxEntries > 10_000) {
            throw new IllegalArgumentException("reconcile.ai.cache.l1.max-entries must be within [1, 10000]");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("reconcile.ai.cache.l1.ttl must be positive");
        }
    }
}
