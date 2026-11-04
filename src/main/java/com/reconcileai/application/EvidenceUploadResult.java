package com.reconcileai.application;

import com.reconcileai.domain.Evidence;

/**
 * Result of an upload attempt. {@code deduplicated == true} means an identical file was
 * already attached to this break and the existing record is returned unchanged (FR-03).
 */
public record EvidenceUploadResult(Evidence evidence, boolean deduplicated) {}
