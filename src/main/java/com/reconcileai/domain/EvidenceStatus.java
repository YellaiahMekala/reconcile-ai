// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/**
 * Evidence processing status, independent of the parent break's own status (FR-04).
 * PROCESSING is set on upload; text extraction moves it to READY or FAILED.
 * Investigation intake returns 409 unless evidence is READY.
 */
public enum EvidenceStatus {
    PROCESSING,
    READY,
    FAILED
}
