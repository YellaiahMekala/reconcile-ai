// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chunking knobs (reconcile.ingestion.*). These are TOKEN-OPTIMIZATION controls:
 * chunk size drives both embedding cost and how much context each citation drags into a prompt.
 */
@ConfigurationProperties("reconcile.ingestion")
public record IngestionProperties(
    int chunkSizeTokens,
    int minChunkSizeChars,
    int minChunkLengthToEmbed,
    int maxChunksPerEvidence
) {}
