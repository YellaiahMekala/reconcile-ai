package com.reconcileai.application.knowledge;

/**
 * One accepted procedure passage from EKA, already masked, screened and truncated.
 *
 * @param ref        EKA chunk id, prefixed {@code kb:} so it can never be mistaken for a break evidence chunk id
 * @param documentId EKA document id
 * @param fileName   EKA file name (display only)
 * @param snippet    masked, truncated passage text; untrusted data
 */
public record KnowledgeHit(String ref, String documentId, String fileName, String snippet) {

    public static final String REF_PREFIX = "kb:";
}
