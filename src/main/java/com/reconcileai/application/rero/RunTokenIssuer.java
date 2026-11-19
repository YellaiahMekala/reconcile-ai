// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.rero;

import java.time.Duration;
import java.util.UUID;

/**
 * Mints the per-job run capability token RERO forwards to reconcile-ai's internal read-only API (TDD 2.3).
 * The token is held only in memory for the duration of one start request: never logged, never persisted.
 */
@FunctionalInterface
public interface RunTokenIssuer {

    /** @return a compact ES256 JWS for exactly this tenant and job, valid for {@code ttl} */
    String mint(String tenantId, UUID jobId, Duration ttl);
}
