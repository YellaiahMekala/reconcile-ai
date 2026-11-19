// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * {@code reconcile.idempotency.*}: the optional {@code Idempotency-Key} header on POST endpoints that create things
 * (platform D1.1.4).
 *
 * @param enabled       on by default; the header itself stays optional (absent header = unchanged behaviour)
 * @param ttl           how long a key is remembered (24h default); expired rows are purged and the key is reusable
 * @param inProgressWait how long a duplicate waits for the first request's response before answering 409
 * @param staleAfter    an IN_PROGRESS row older than this is treated as abandoned (crashed node) and taken over
 * @param purgeIntervalMs purge job interval
 * @param paths         POST path patterns covered (Spring {@code AntPathMatcher} syntax)
 */
@ConfigurationProperties("reconcile.idempotency")
public record IdempotencyProperties(Boolean enabled, Duration ttl, Duration inProgressWait, Duration staleAfter,
                                    Long purgeIntervalMs, List<String> paths) {

    public static final List<String> DEFAULT_PATHS = List.of(
        "/api/v1/breaks", "/api/v1/breaks/*/evidence", "/api/v1/breaks/*/investigations");

    public IdempotencyProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
        inProgressWait = inProgressWait == null ? Duration.ofSeconds(10) : inProgressWait;
        staleAfter = staleAfter == null ? Duration.ofMinutes(5) : staleAfter;
        purgeIntervalMs = purgeIntervalMs == null || purgeIntervalMs <= 0 ? 3_600_000L : purgeIntervalMs;
        paths = paths == null || paths.isEmpty() ? DEFAULT_PATHS : List.copyOf(paths);
    }
}
