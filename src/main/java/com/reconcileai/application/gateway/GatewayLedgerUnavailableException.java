package com.reconcileai.application.gateway;

/**
 * 503 {@code GATEWAY_LEDGER_UNAVAILABLE}: a reconcile named a gateway invocation id, but the gateway ledger could not
 * be read, so nothing is recorded. The person retries later or reconciles with a ticket reference instead.
 */
public class GatewayLedgerUnavailableException extends RuntimeException {

    public GatewayLedgerUnavailableException(String message) {
        super(message);
    }
}
