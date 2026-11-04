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
