// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.knowledge;

/**
 * Tenant-isolated procedure knowledge from EKA (platform Q1, TDD section 4).
 * <p>
 * The tenant is the job's tenant, passed by the caller from the job row; it is never taken from a request body. The
 * implementation masks the query, calls EKA's search endpoint only (never chat), screens and truncates the hits, and
 * always returns an outcome instead of throwing, so the caller decides what a degraded result means
 * ({@code KNOWLEDGE_FAIL_CLOSED}).
 * <p>
 * Knowledge hits are "procedure consulted" references. They are never break evidence: they do not enter the
 * retrieval snapshot and cannot satisfy the citation verifier.
 */
@FunctionalInterface
public interface KnowledgeSearchPort {

    KnowledgeResult search(String tenantId, String query);
}
