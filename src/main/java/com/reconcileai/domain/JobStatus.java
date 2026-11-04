package com.reconcileai.domain;

/** Lifecycle of an async investigation job. Exactly one QUEUED/RUNNING job per break (DB partial unique index). */
public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED
}
