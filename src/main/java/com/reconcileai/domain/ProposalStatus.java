// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/**
 * Proposal lifecycle. A proposal is NEVER itself a resolution: persistProposal() only creates one of
 * these; only ResolutionCommitService moves the break to RESOLVED / AUTO_RESOLVED.
 */
public enum ProposalStatus {
    PENDING_APPROVAL,   // routed to L1 or L2 approver
    MANUAL_REVIEW,      // policy refused an approval route (low confidence, bad citations, unknown cause)
    APPROVED,           // checker approved -> break RESOLVED
    REJECTED,           // checker rejected
    AUTO_RESOLVED       // only reachable if auto-resolve is explicitly enabled
}
