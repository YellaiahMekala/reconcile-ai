// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api.dto;

import com.reconcileai.domain.Evidence;

import java.time.Instant;
import java.util.UUID;

/**
 * Evidence metadata returned to clients. File bytes are never echoed back.
 * {@code deduplicated=true} means this exact content was already attached to the break (HTTP 200).
 */
public record EvidenceResponse(
    UUID id,
    UUID breakItemId,
    String fileName,
    String contentType,
    String contentHash,
    long sizeBytes,
    String status,
    boolean deduplicated,
    Integer chunkCount,
    Integer suspectChunkCount,
    String failureReason,
    Instant createdAt
) {
    public static EvidenceResponse from(Evidence e, boolean deduplicated) {
        return new EvidenceResponse(
            e.getId(), e.getBreakItemId(), e.getFileName(), e.getContentType(),
            e.getContentHash(), e.getSizeBytes(), e.getStatus().name(),
            deduplicated, e.getChunkCount(), e.getSuspectChunkCount(), e.getFailureReason(),
            e.getCreatedAt());
    }
}
