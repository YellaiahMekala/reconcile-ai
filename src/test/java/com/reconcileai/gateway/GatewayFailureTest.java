// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.gateway;

import com.reconcileai.application.gateway.GatewayCallException;
import com.reconcileai.application.gateway.GatewayFailure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every code in ai-gateway CONSUMER_CONTRACT.md section 3 maps to a short reason; unknown outcomes and policy
 * blocks are distinct, and the two sets never overlap.
 */
class GatewayFailureTest {

    private static final List<String> CONTRACT_CODES = List.of(
        "MISSING_HEADER", "INVALID_REQUEST", "TENANT_OVERRIDE_REJECTED", "UNAUTHENTICATED", "FORBIDDEN",
        "PROMPT_NOT_FOUND", "IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_UNKNOWN_OUTCOME", "REQUEST_TOO_LARGE",
        "IDEMPOTENCY_KEY_REUSED", "RISK_BLOCKED", "SENSITIVITY_BLOCKED", "RATE_LIMITED", "TENANT_CONCURRENCY_LIMIT",
        "BUDGET_BLOCKED", "PROMPT_INTEGRITY_FAILURE", "PROVIDER_CONTRACT_FAILURE", "NOT_SENT", "RETRYABLE_FAILURE",
        "OVERLOADED", "MODEL_UNAVAILABLE", "GOVERNANCE_UNAVAILABLE", "NO_ELIGIBLE_MODEL", "UNKNOWN_OUTCOME",
        "TRANSPORT_NOT_SENT", "TRANSPORT_UNKNOWN_OUTCOME");

    private static GatewayCallException failure(String code) {
        return new GatewayCallException(503, code, UUID.randomUUID());
    }

    @Test
    void everyContractCodeGetsAPrefixedFitForTheJobRow() {
        for (String code : CONTRACT_CODES) {
            String reason = GatewayFailure.reason(failure(code));
            assertThat(reason).isEqualTo("GATEWAY_" + code);
            assertThat(reason).matches("[A-Z_]{3,64}");
        }
    }

    @Test
    void onlyCodesWhereTheCallMayHaveRunAreOutcomeUnknown() {
        List<String> unknown = CONTRACT_CODES.stream().filter(c -> GatewayFailure.outcomeUnknown(failure(c))).toList();
        assertThat(unknown).containsExactlyInAnyOrder(
            "UNKNOWN_OUTCOME", "IDEMPOTENCY_UNKNOWN_OUTCOME", "TRANSPORT_UNKNOWN_OUTCOME", "IDEMPOTENCY_IN_PROGRESS");
    }

    @Test
    void onlyContentAndDataLevelRefusalsArePolicyBlocked() {
        List<String> blocked = CONTRACT_CODES.stream().filter(c -> GatewayFailure.policyBlocked(failure(c))).toList();
        assertThat(blocked).containsExactlyInAnyOrder("RISK_BLOCKED", "SENSITIVITY_BLOCKED");
        // A policy block is a certain refusal: it can never also be an unknown outcome.
        for (String code : blocked) {
            assertThat(GatewayFailure.outcomeUnknown(failure(code))).as(code).isFalse();
        }
        // Budget and other technical refusals stay retryable, not manual review.
        assertThat(GatewayFailure.policyBlocked(failure("BUDGET_BLOCKED"))).isFalse();
        assertThat(GatewayFailure.policyBlocked(failure("RATE_LIMITED"))).isFalse();
    }

    @Test
    void anUnexpectedCodeNeverLeaksIntoTheJobRow() {
        assertThat(GatewayFailure.reason(failure("some free text, with detail 123"))).isEqualTo("GATEWAY_ERROR");
        assertThat(GatewayFailure.reason(failure(null))).isEqualTo("GATEWAY_ERROR");
        assertThat(GatewayFailure.outcomeUnknown(failure(null))).isFalse();
        assertThat(GatewayFailure.policyBlocked(failure(null))).isFalse();
    }
}
