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
}
