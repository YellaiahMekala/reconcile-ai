// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.gateway;

import com.reconcileai.support.AbstractGatewayIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.FakeGatewayServer.Call;
import com.reconcileai.support.FakeGatewayServer.Scripted;
import com.reconcileai.support.InvestigationTestSupport;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q2.5: what the gateway must never receive, and what happens when its policy refuses the call anyway.
 * <ol>
 *   <li>Structured identifiers in the evidence (IBAN, card number) are masked before the request leaves
 *       reconcile-ai; the gateway sees {@code [IBAN]} and {@code [CARD_NUMBER]}, never the values.</li>
 *   <li>A {@code SENSITIVITY_BLOCKED} or {@code RISK_BLOCKED} refusal is a business fact about the break, not a
 *       technical failure: the job fails with that reason, no proposal exists, and the break goes to
 *       MANUAL_REVIEW_REQUIRED (a person), not to FAILED_RETRYABLE (the sweeper). The break is not outcome-unknown,
 *       so an analyst may re-run it once the input has been changed.</li>
 * </ol>
 */
class GatewaySensitiveDataIT extends AbstractGatewayIT {

    private static final String RAW_IBAN_START = "DE89 3704";
    private static final String RAW_CARD_START = "4111 1111";

    @Test
    void identifiersInTheEvidenceAreMaskedBeforeTheGatewaySeesThem() {
        String analyst = analyst("TENANT_GW_PII");

        JsonPath job = investigate(analyst, "/fixtures/evidence/settlement-with-iban.txt");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        List<Call> calls = gateway.calls();
        assertThat(calls).hasSize(1);
        String wire = calls.get(0).body();
        assertThat(wire).doesNotContain(RAW_IBAN_START).doesNotContain("DE893704")
            .doesNotContain(RAW_CARD_START).doesNotContain("4111111111111111");
        assertThat(wire).contains("[IBAN]").contains("[CARD_NUMBER]");
        // The evidence itself still reached the model (masked), so the proposal cites it and is grounded.
        JsonPath proposal = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(proposal.getBoolean("citationsValid")).isTrue();
        assertThat(proposal.getString("rootCause")).isEqualTo("TIMING_DIFFERENCE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENSITIVITY_BLOCKED", "RISK_BLOCKED"})
    void aPolicyRefusalSendsTheBreakToAPersonNotToTheRetrySweeper(String code) {
        String tenant = "TENANT_GW_" + code;
        String analyst = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/settlement-with-iban.txt", "text/plain");
        gateway.enqueue(Scripted.refusal(422, code));

        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, jobId);

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_" + code);
        assertThat(job.getString("proposalId")).isNull();
        assertThat(breakStatus(analyst, breakId)).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolution_proposal WHERE break_item_id = ?::uuid",
            Integer.class, breakId)).isZero();

        Map<String, Object> row = jobRow(jobId);
        assertThat(row.get("outcome_unknown")).isEqualTo(Boolean.FALSE);
        assertThat(failureAudit(tenant, breakId))
            .contains("\"gatewayPolicyBlocked\":true")
            .contains("\"route\":\"MANUAL_REVIEW\"")
            .contains("\"reason\":\"GATEWAY_" + code + "\"");
        assertThat(gateway.calls()).as("a policy refusal is never retried").hasSize(1);
        assertThat(gatewayCalls(jobId)).hasSize(1);
        assertThat(gatewayCalls(jobId).get(0).get("outcome")).isEqualTo("REFUSED");

        // Not outcome-unknown: a person may re-run it (new job, new key) without the Q2.3 reconcile step.
        String rerunId = InvestigationTestSupport.start(analyst, breakId, null);
        assertThat(InvestigationTestSupport.awaitTerminal(analyst, rerunId).getString("status")).isEqualTo("SUCCEEDED");
        assertThat(gateway.calls()).extracting(Call::idempotencyKey).containsExactly(keyFor(jobId), keyFor(rerunId));
    }
}
