package com.reconcileai.infrastructure.ai.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * reconcile.ai.cache.* — semantic cache for investigation drafts.
 *
 * @param enabled             off by default; when on, Redis must be reachable (lookups fail open if it is not)
 * @param similarityThreshold minimum cosine similarity for a semantic (non-exact) hit
 * @param ttlHours            entry lifetime
 * @param maxEntriesPerTenant bounded scan set per tenant (newest first); older entries are evicted
 * @param namespace           bump to invalidate every entry (prompt or model change)
 */
@ConfigurationProperties("reconcile.ai.cache")
public record SemanticCacheProperties(
    boolean enabled,
    double similarityThreshold,
    long ttlHours,
    int maxEntriesPerTenant,
    String namespace
) {
    public SemanticCacheProperties {
        if (similarityThreshold < 0.80 || similarityThreshold > 1.0) {
            // Below 0.80, "similar" prompts stop meaning "same question": refuse a config that would serve wrong answers.
            throw new IllegalArgumentException("reconcile.ai.cache.similarity-threshold must be within [0.80, 1.0]");
        }
        if (maxEntriesPerTenant < 1 || maxEntriesPerTenant > 5000) {
            throw new IllegalArgumentException("reconcile.ai.cache.max-entries-per-tenant must be within [1, 5000]");
        }
        if (namespace == null || namespace.isBlank()) {
            namespace = "v1";
        }
    }
}
