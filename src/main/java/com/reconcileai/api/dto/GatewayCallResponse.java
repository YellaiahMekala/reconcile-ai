// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api.dto;

import com.reconcileai.application.gateway.GatewayLedgerPort;
import com.reconcileai.application.investigation.InvestigationService.GatewayCallView;
import com.reconcileai.domain.GatewayCallRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One gateway call of a job: our {@code gateway_call} row plus the gateway ledger's view of the invocation.
 * {@code ledgerResult} is FOUND, NOT_FOUND, UNAVAILABLE, or NOT_CHECKED (no invocation id, or the gateway route is off).
 * No prompt, completion or token value is ever included.
 */
public record GatewayCallResponse(
    int attempt, String idempotencyKey, String outcome, int httpStatus, String gatewayCode, UUID invocationId,
    String gatewayStatus, int inputTokens, int outputTokens, long latencyMs, Instant createdAt,
    String ledgerResult, String ledgerStatus, String ledgerExecutionCertainty, String ledgerModelKey,
    String ledgerProvider, Integer ledgerInputTokens, Integer ledgerOutputTokens, String ledgerUsageSource,
    BigDecimal ledgerCostUsd
) {
    public static GatewayCallResponse from(GatewayCallView view) {
        GatewayCallRecord c = view.call();
        GatewayLedgerPort.Lookup l = view.ledger();
        GatewayLedgerPort.Entry e = l == null ? null : l.entry();
        return new GatewayCallResponse(c.getAttempt(), c.getIdempotencyKey(), c.getOutcome(), c.getHttpStatus(),
            c.getCode(), c.getInvocationId(), c.getGatewayStatus(), c.getInputTokens(), c.getOutputTokens(),
            c.getLatencyMs(), c.getCreatedAt(),
            l == null ? "NOT_CHECKED" : l.result().name(),
            e == null ? null : e.status(), e == null ? null : e.executionCertainty(),
            e == null ? null : e.modelKey(), e == null ? null : e.provider(),
            e == null ? null : e.inputTokens(), e == null ? null : e.outputTokens(),
            e == null ? null : e.usageSource(), e == null ? null : e.actualCostUsd());
    }
}
