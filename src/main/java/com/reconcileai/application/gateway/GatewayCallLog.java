// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.gateway;

import java.util.UUID;

/**
 * Our side of the gateway ledger (platform Q2.4): one row per HTTP attempt, so every terminal path can be reconciled
 * as {@code jobId -> idempotencyKey -> invocationId} against the gateway's own ledger ({@code GET /api/v1/invocations/{id}}).
 * <p>
 * A transport retry under the same key is a second row with {@code attempt = 2}; the key never changes. Nothing here
 * decides anything: the adapter still applies the retry rule, this only records what it did.
 */
public interface GatewayCallLog {

    /** What happened on one attempt. {@code invocationId} is null when the gateway sent none (or nothing came back). */
    enum Outcome {
        /** 200: the gateway answered with a result (OK, replay, cache hit or fallback). */
        SUCCESS,
        /** A non-2xx answer after which nothing ran on the provider (budget, risk, schema, not-sent codes). */
        REFUSED,
        /** The call may have run and been billed: 504, 409 IDEMPOTENCY_UNKNOWN_OUTCOME, or a timeout after sending. */
        UNKNOWN,
        /** Nothing reached the gateway (connection refused). */
        NOT_SENT
    }

    record Attempt(String tenantId, UUID jobId, String idempotencyKey, int attempt, Outcome outcome,
                   int httpStatus, String code, UUID invocationId, String gatewayStatus,
                   int inputTokens, int outputTokens, long latencyMs) {
    }

    void record(Attempt attempt);

    GatewayCallLog NOOP = attempt -> { };
}
