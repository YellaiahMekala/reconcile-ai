// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.rero;

import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Persistent RERO run state in reconcile-ai's database (tenant-scoped, RLS applies). Platform Q3.2.
 */
public interface ReroRunStore {

    /**
     * Counts one tool call atomically: a single conditional UPDATE that only matches while the job is RUNNING, in RERO
     * mode and under its budget. Concurrent callers can never push the counter past the maximum.
     *
     * @return the new count (1-based sequence of this call), or empty when nothing was counted
     */
    OptionalInt tryConsumeToolCall(String tenantId, UUID jobId);

    /** Adds chunk ids to the set served to this job (idempotent). */
    void recordServedChunks(String tenantId, UUID jobId, Collection<String> chunkIds);

    /** Every chunk id the internal evidence tool served to this job. The only ids a RERO result may cite. */
    List<String> servedChunkIds(String tenantId, UUID jobId);

    /** One audited tool call, without arguments. {@code seq} is the counter value from {@link #tryConsumeToolCall}. */
    void recordToolCall(String tenantId, UUID jobId, int seq, String tool, int resultChars, String outcome, long latencyMs);
}
