// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.knowledge;

/**
 * The four knowledge outcomes of platform TDD 4.2. They stay distinct all the way to the job row and the API.
 * <ul>
 *   <li>{@link #OK}: at least one accepted hit; supplied to the model as reference data.</li>
 *   <li>{@link #EMPTY}: EKA answered normally with no hits; the investigation continues without knowledge.</li>
 *   <li>{@link #UNAVAILABLE}: timeout, 5xx, rate limit, open breaker, token or auth fault. Default: continue and
 *       record the degraded context; with {@code KNOWLEDGE_FAIL_CLOSED=true} the job fails retryably.</li>
 *   <li>{@link #REJECTED}: local policy refused the query or every hit; no rejected text reaches the model.</li>
 * </ul>
 */
public enum KnowledgeOutcome {
    OK, EMPTY, UNAVAILABLE, REJECTED
}
