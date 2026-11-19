// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
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
