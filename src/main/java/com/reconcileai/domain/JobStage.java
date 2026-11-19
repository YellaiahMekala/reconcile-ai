// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/** Fine-grained progress for polling and SSE lifecycle events. Never carries model output. */
public enum JobStage {
    QUEUED, RETRIEVING, CALLING_MODEL, VERIFYING, ROUTING, COMPLETED, FAILED
}
