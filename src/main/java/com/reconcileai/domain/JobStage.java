package com.reconcileai.domain;

/** Fine-grained progress for polling and SSE lifecycle events. Never carries model output. */
public enum JobStage {
    QUEUED, RETRIEVING, CALLING_MODEL, VERIFYING, ROUTING, COMPLETED, FAILED
}
