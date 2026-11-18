package com.reconcileai.application.knowledge;

import java.util.List;

/**
 * One knowledge lookup.
 *
 * @param outcome one of the four outcomes
 * @param reason  short non-sensitive code ({@code TIMEOUT}, {@code HTTP_503}, {@code AUTH}, {@code CIRCUIT_OPEN},
 *                {@code ALL_HITS_SCREENED}, ...); null for {@code OK} and {@code EMPTY}. Never contains document text
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
