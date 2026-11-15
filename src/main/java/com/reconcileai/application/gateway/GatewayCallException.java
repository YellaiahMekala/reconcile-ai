package com.reconcileai.application.gateway;

import java.util.UUID;

/**
 * A non-2xx answer from ai-gateway, or a transport failure. {@code code} is the gateway's {@code code} field
 * (CONSUMER_CONTRACT.md §3), or {@code TRANSPORT_NOT_SENT} / {@code TRANSPORT_UNKNOWN_OUTCOME} when no answer came.
 * Typed mapping of each code to job behaviour is platform Q2.2.
 */
public class GatewayCallException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final UUID invocationId;

    public GatewayCallException(int httpStatus, String code, UUID invocationId) {
        super("ai-gateway refused the call: " + httpStatus + " " + code);
        this.httpStatus = httpStatus;
        this.code = code;
        this.invocationId = invocationId;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    /** The gateway ledger row, when one exists; on IDEMPOTENCY_UNKNOWN_OUTCOME it is the earlier call's row. */
    public UUID invocationId() {
        return invocationId;
    }
}
