// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/**
 * Lifecycle of a BreakItem: OPEN through RESOLVED, including investigation, proposal, routing and approval states.
 */
public enum BreakStatus {
    OPEN,
    EVIDENCE_PROCESSING,
    READY_TO_INVESTIGATE,
    INVESTIGATING,
    PROPOSAL_CREATED,
    PENDING_L1,
    PENDING_L2,
    AUTO_RESOLVED,
    RESOLVED,
    REJECTED,
    MANUAL_REVIEW_REQUIRED,
    FAILED_RETRYABLE
}
