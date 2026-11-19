// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application;

import com.reconcileai.domain.Evidence;

/**
 * Result of an upload attempt. {@code deduplicated == true} means an identical file was
 * already attached to this break and the existing record is returned unchanged (FR-03).
 */
public record EvidenceUploadResult(Evidence evidence, boolean deduplicated) {}
