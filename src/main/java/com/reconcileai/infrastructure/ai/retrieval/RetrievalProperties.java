package com.reconcileai.infrastructure.ai.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Hybrid retrieval knobs (reconcile.retrieval.*). See README "Retrieval tuning" for how to set the floor. */
@ConfigurationProperties("reconcile.retrieval")
public record RetrievalProperties(
    int vectorTopK,
    int keywordTopK,
    double similarityThreshold,
    int rrfK,
    int maxResults
) {}
