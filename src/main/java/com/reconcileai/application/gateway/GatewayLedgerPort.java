// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of the ai-gateway ledger: {@code GET /api/v1/invocations/{id}}. Exists only when the gateway route
 * is on. Never retried and never used to decide anything on its own: it is what an approver sees, and what is
 * recorded, when an unknown outcome is reconciled.
 * <p>
 * The gateway scopes the ledger by the token's tenant, so the lookup is made with the job tenant's token. Another
 * tenant's row answers {@link Result#NOT_FOUND}, exactly like an id that does not exist.
 */
public interface GatewayLedgerPort {

    enum Result { FOUND, NOT_FOUND, UNAVAILABLE }

    /** The gateway's InvocationView, minus correlation and latency fields nobody decides on. */
    record Entry(UUID invocationId, String status, String executionCertainty, String modelKey, String provider,
                 int inputTokens, int outputTokens, String usageSource, BigDecimal actualCostUsd, Instant createdAt) { }

    /** {@code entry} is set only for {@link Result#FOUND}. */
    record Lookup(Result result, Entry entry) {

        public static Lookup found(Entry entry) {
            return new Lookup(Result.FOUND, entry);
        }

        public static Lookup notFound() {
            return new Lookup(Result.NOT_FOUND, null);
        }

        public static Lookup unavailable() {
            return new Lookup(Result.UNAVAILABLE, null);
        }
    }

    Lookup lookup(String tenantId, UUID invocationId);
}
