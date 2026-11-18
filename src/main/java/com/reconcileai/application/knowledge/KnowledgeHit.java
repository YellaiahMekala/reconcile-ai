package com.reconcileai.application.knowledge;

/**
 * One accepted procedure passage from EKA, already masked, screened and truncated.
 *
 * @param ref        EKA chunk id, prefixed {@code kb:} so it can never be mistaken for a break evidence chunk id
 * @param documentId EKA document id
 * @param fileName   EKA file name (display only)
 * @param snippet    masked, truncated passage text; untrusted data
 * @param score      EKA's {@code scores.cosine} for this hit (contract field, required by EKA's schema); null when
 *                   absent. Used only by {@link KnowledgeRelevanceFilter}, never shown to the model
 */
public record KnowledgeHit(String ref, String documentId, String fileName, String snippet, Double score) {

    public static final String REF_PREFIX = "kb:";

    /** A hit without a score (tests, older callers). */
    public KnowledgeHit(String ref, String documentId, String fileName, String snippet) {
        this(ref, documentId, fileName, snippet, null);
    }
}
