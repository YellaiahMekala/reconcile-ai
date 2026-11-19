// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.security;

/**
 * Per-key request limiter (the key is the tenant id). Two implementations: {@link TokenBucketRateLimiter} (one
 * bucket per JVM, the default) and {@link RedisTokenBucketRateLimiter} (one bucket shared by every replica).
 */
public interface RateLimiter {

    /** Result of {@link #tryAcquire}: allowed, or how many seconds the caller should wait. */
    record Decision(boolean allowed, long retryAfterSeconds) {}

    Decision tryAcquire(String key);
}
