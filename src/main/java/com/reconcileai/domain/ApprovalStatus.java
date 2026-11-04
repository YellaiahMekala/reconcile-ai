package com.reconcileai.domain;

/** Approval task lifecycle. OPEN is the only state a checker can act on. */
public enum ApprovalStatus {
    OPEN, APPROVED, REJECTED
}
