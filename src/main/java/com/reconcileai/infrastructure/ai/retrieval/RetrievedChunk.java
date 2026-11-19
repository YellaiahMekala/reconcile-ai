// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.retrieval;

import java.util.UUID;

/**
 * One evidence chunk returned by hybrid retrieval.
 *
 * @param chunkId      vector_store row id; this is the id the LLM must cite
 * @param vectorScore  cosine similarity (1 - distance) if the vector leg found it, else null
 * @param keywordRank  1-based rank in the BM25 leg if found there, else null
 * @param fusedScore   Reciprocal Rank Fusion score used for final ordering
 */
public record RetrievedChunk(
    String chunkId,
    UUID evidenceId,
    int chunkIndex,
    String fileName,
    String text,
    Double vectorScore,
    Integer keywordRank,
    double fusedScore
) {
    /** Rough token estimate (≈4 chars/token for English). Good enough for budgeting; not for billing. */
    public int estimatedTokens() {
        return Math.max(1, text.length() / 4);
    }
}
