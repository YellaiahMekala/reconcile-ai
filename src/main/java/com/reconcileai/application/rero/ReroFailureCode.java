// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.rero;

/**
 * RERO's {@code failureCode} values (Contract C section 1). Each maps to a retryable job failure {@code RERO_<code>}
 * with no proposal. {@link #UNRECOGNISED} stands for any value outside the contract.
 * <p>
 * {@code UNKNOWN_OUTCOME} (a gateway call may have run) is NOT turned into reconcile-ai's outcome-unknown re-run block:
 * RERO's gateway keys are {@code {jobId}:draft} / {@code {jobId}:selfcheck}, so a new job can never replay or double
 * an earlier job's call, and the gateway invocation ids RERO reported are kept in the audit entry for a person who wants
 * to check the ledger. {@code MODEL_BLOCKED} is retryable too; routing a blocked input to a person is left to the
 * analyst, because RERO does not tell us whether the block was input sensitivity or a transient governance state.
 */
public enum ReroFailureCode {
    DEADLINE_EXCEEDED, TOOL_BUDGET_EXCEEDED, TOOL_UNAVAILABLE, TOOL_REJECTED, MODEL_UNAVAILABLE, MODEL_BLOCKED,
    UNKNOWN_OUTCOME, INVALID_MODEL_OUTPUT, INVALID_CITATION, RESTARTED, UNRECOGNISED;

    public static ReroFailureCode parse(String raw) {
        if (raw == null) {
            return UNRECOGNISED;
        }
        for (ReroFailureCode c : values()) {
            if (c != UNRECOGNISED && c.name().equals(raw)) {
                return c;
            }
        }
        return UNRECOGNISED;
    }
}
