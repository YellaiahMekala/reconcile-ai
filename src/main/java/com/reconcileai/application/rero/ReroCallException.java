package com.reconcileai.application.rero;

import java.util.List;

/**
 * A RERO investigation that produced no usable recommendation. {@code code} is short, non-sensitive and stored as the
 * job's failure reason ({@code RERO_*}). Every RERO failure is retryable: the job fails, the break goes to
 * FAILED_RETRYABLE and no proposal is written (TDD section 7). An analyst may start a new job (new job id, new
 * idempotency key, new gateway keys).
 */
public class ReroCallException extends RuntimeException {

    /** Transport and protocol failures on reconcile-ai's side of Contract C. */
    public static final String UNAVAILABLE = "RERO_UNAVAILABLE";
    public static final String TOKEN_UNAVAILABLE = "RERO_TOKEN_UNAVAILABLE";
    public static final String AUTH = "RERO_AUTH_REJECTED";
    public static final String IDEMPOTENCY_CONFLICT = "RERO_IDEMPOTENCY_KEY_REUSED";
    public static final String INVALID_RESPONSE = "RERO_INVALID_RESPONSE";
    public static final String DEADLINE = "RERO_DEADLINE_EXCEEDED";
    public static final String INVALID_CITATION = "RERO_INVALID_CITATION";
    public static final String DISABLED = "RERO_DISABLED";

    private final String code;
    private final List<String> gatewayInvocationIds;

    public ReroCallException(String code) {
        this(code, List.of());
    }

    public ReroCallException(String code, List<String> gatewayInvocationIds) {
        super(code);
        this.code = code;
        this.gatewayInvocationIds = gatewayInvocationIds == null ? List.of() : List.copyOf(gatewayInvocationIds);
    }

    /** A FAILED run: RERO's own {@code failureCode}, prefixed, unknown values collapsed so nothing odd is stored. */
    public static ReroCallException runFailed(String failureCode, List<String> gatewayInvocationIds) {
        return new ReroCallException("RERO_" + ReroFailureCode.parse(failureCode).name(), gatewayInvocationIds);
    }

    public String code() {
        return code;
    }

    public List<String> gatewayInvocationIds() {
        return gatewayInvocationIds;
    }

    /** All RERO failures are retryable by policy (TDD 7); kept as a method so callers do not assume otherwise. */
    public boolean retryable() {
        return true;
    }
}
