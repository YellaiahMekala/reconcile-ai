// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/** Lifecycle of an async investigation job. Exactly one QUEUED/RUNNING job per break (DB partial unique index). */
public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED
}
