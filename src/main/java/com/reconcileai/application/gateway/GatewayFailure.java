// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.gateway;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed mapping of an ai-gateway refusal to the job's failure reason (platform Q2.2). The reason is a short,
 * non-sensitive code, {@code GATEWAY_<code>}, so operators and metrics can tell the cases apart.
 * <p>
 * Whether the call may have run matters more than the cause: after {@link #outcomeUnknown} the gateway (and the
 * provider) may have executed it and billed it, so the job is failed with a distinct reason and the gateway's
 * {@code invocationId} is kept in the audit entry for reconciliation. Nothing here retries; the one permitted
 * transport retry already happened in the adapter (CONSUMER_CONTRACT.md §4).
 */
public final class GatewayFailure {

    /** Codes after which the call may have executed: never retry, reconcile via the gateway ledger first. */
    private static final Set<String> OUTCOME_UNKNOWN = Set.of(
        "UNKNOWN_OUTCOME", "IDEMPOTENCY_UNKNOWN_OUTCOME", "TRANSPORT_UNKNOWN_OUTCOME", "IDEMPOTENCY_IN_PROGRESS");
    /**
     * Codes where the gateway's policy refused the content or its data level (platform Q2.5). The call did not run,
     * but the refusal is a business fact about this break, so it goes to a person (manual review), not to the retry
     * sweeper: re-running the same input would be refused again.
     */
    private static final Set<String> POLICY_BLOCKED = Set.of("RISK_BLOCKED", "SENSITIVITY_BLOCKED");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z_]{3,48}");
    static final String UNMAPPED = "GATEWAY_ERROR";

    private GatewayFailure() {
    }

    public static String reason(GatewayCallException e) {
        String code = e.code();
        return code != null && SAFE_CODE.matcher(code).matches() ? "GATEWAY_" + code : UNMAPPED;
    }

    public static boolean outcomeUnknown(GatewayCallException e) {
        return e.code() != null && OUTCOME_UNKNOWN.contains(e.code());
    }

    /** True for a content or data-level refusal: the break goes to manual review instead of FAILED_RETRYABLE. */
    public static boolean policyBlocked(GatewayCallException e) {
        return e.code() != null && POLICY_BLOCKED.contains(e.code());
    }
}
