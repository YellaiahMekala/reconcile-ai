// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
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
