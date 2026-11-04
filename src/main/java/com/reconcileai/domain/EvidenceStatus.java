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
