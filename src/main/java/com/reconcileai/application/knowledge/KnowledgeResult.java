// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.knowledge;

import java.util.List;

/**
 * One knowledge lookup.
 *
 * @param outcome one of the four outcomes
 * @param reason  short non-sensitive code ({@code TIMEOUT}, {@code HTTP_503}, {@code AUTH}, {@code CIRCUIT_OPEN},
 *                {@code ALL_HITS_SCREENED}, ...); null for {@code OK}, null for {@code EMPTY} unless the relevance filter
 *                dropped every hit ({@code FILTERED_LOW_RELEVANCE}). Never contains document text
 * @param hits    accepted hits, best first; empty unless {@code OK}
 */
public record KnowledgeResult(KnowledgeOutcome outcome, String reason, List<KnowledgeHit> hits) {

    public KnowledgeResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        if (outcome == KnowledgeOutcome.OK && hits.isEmpty()) {
            throw new IllegalArgumentException("OK needs at least one hit");
        }
        if (outcome != KnowledgeOutcome.OK && !hits.isEmpty()) {
            throw new IllegalArgumentException(outcome + " carries no hits");
        }
    }

    public static KnowledgeResult ok(List<KnowledgeHit> hits) {
        return new KnowledgeResult(KnowledgeOutcome.OK, null, hits);
    }

    public static KnowledgeResult empty() {
        return new KnowledgeResult(KnowledgeOutcome.EMPTY, null, List.of());
    }

    /** Every hit EKA returned was dropped by the relevance filter (D1.1.1): EMPTY with a reason. */
    public static KnowledgeResult filteredOut() {
        return new KnowledgeResult(KnowledgeOutcome.EMPTY, KnowledgeRelevanceFilter.FILTERED_LOW_RELEVANCE, List.of());
    }

    public static KnowledgeResult unavailable(String reason) {
        return new KnowledgeResult(KnowledgeOutcome.UNAVAILABLE, reason, List.of());
    }

    public static KnowledgeResult rejected(String reason) {
        return new KnowledgeResult(KnowledgeOutcome.REJECTED, reason, List.of());
    }

    /** The "procedure consulted" references stored on the job: EKA chunk ids, never break evidence ids. */
    public List<String> refs() {
        return hits.stream().map(KnowledgeHit::ref).toList();
    }
}
