// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.gateway;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Idempotency keys for ai-gateway: {@code recon:<jobId>:<node>:<attempt>}.
 * <p>
 * {@code node} is the workflow step (for example {@code investigate}), not the cluster node: a job re-claimed by
 * another instance must present the same key, or the gateway would treat it as a new, billable call.
 * {@code attempt} is the logical attempt of that step inside the job; transport retries never change it. A new
 * attempt after an unknown outcome is a new job with a new {@code jobId} (platform Q2.3), not a higher number here.
 * <p>
 * The gateway accepts 8 to 128 characters of {@code [A-Za-z0-9._:-]} (ai-gateway {@code ChatController.java:36}).
 */
public final class IdempotencyKeys {

    public static final Pattern GATEWAY_RULE = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern STEP = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private IdempotencyKeys() {
    }

    public static String forStep(UUID jobId, String step, int attempt) {
        if (jobId == null || step == null || !STEP.matcher(step).matches() || attempt < 1) {
            throw new IllegalArgumentException("jobId, a lower-case step name and attempt >= 1 are required");
        }
        return requireValid("recon:" + jobId + ":" + step + ":" + attempt);
    }

    public static String requireValid(String key) {
        if (key == null || !GATEWAY_RULE.matcher(key).matches()) {
            throw new IllegalArgumentException("idempotency key does not meet the gateway rule");
        }
        return key;
    }
}
