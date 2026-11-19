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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Platform Q2.3, end to end through the real application: when the gateway answers that the call <b>may</b> have
 * run, reconcile-ai sends exactly one request under the job's key, fails the job as outcome-unknown, and refuses
 * a re-run until a second person has reconciled it against the gateway ledger. The re-run is a new job, so it
 * carries a new key: the old key is never reused.
 */
class GatewayUnknownOutcomeIT extends AbstractGatewayIT {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"504, UNKNOWN_OUTCOME", "409, IDEMPOTENCY_UNKNOWN_OUTCOME"})
    void oneRequestThenBlockedUntilReconciledThenANewKey(int status, String code) {
        String tenant = "TENANT_GW_UNKNOWN_" + status;
        String analyst = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        UUID scriptedInvocation = UUID.randomUUID();
        gateway.enqueue(new Scripted(status, code, scriptedInvocation, "1"));

        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, jobId);

        // The job: failed with the typed reason, flagged, no proposal.
        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_" + code);
        assertThat(job.getString("proposalId")).isNull();
        Map<String, Object> row = jobRow(jobId);
        assertThat(row.get("outcome_unknown")).isEqualTo(Boolean.TRUE);
        assertThat(breakStatus(analyst, breakId)).isEqualTo("FAILED_RETRYABLE");

        // Exactly one request reached the gateway, under the contract key, even though a Retry-After was offered.
        List<Call> calls = gateway.calls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).idempotencyKey()).isEqualTo(keyFor(jobId));

        // Our side of the ledger: one attempt, UNKNOWN, pointing at the gateway's invocation id.
        List<Map<String, Object>> logged = gatewayCalls(jobId);
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).get("outcome")).isEqualTo("UNKNOWN");
        assertThat(logged.get(0).get("http_status")).isEqualTo(status);
        assertThat(logged.get(0).get("code")).isEqualTo(code);
        assertThat(uuid(logged.get(0).get("invocation_id"))).isEqualTo(scriptedInvocation);
        assertThat(failureAudit(tenant, breakId))
            .contains("\"gatewayOutcomeUnknown\":true")
            .contains(scriptedInvocation.toString());

        // The guard: no re-run until someone else reconciles against the gateway ledger.
        given().header("Authorization", analyst).contentType("application/json").body("{}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(409).body("code", equalTo("OUTCOME_UNRECONCILED"));
        assertThat(gateway.calls()).as("a blocked start never reaches the gateway").hasSize(1);

        // The ledger row the operator would check (invocationId from the error body, via GET /api/v1/invocations/{id}).
        given().when().get(gateway.baseUrl() + "/api/v1/invocations/" + scriptedInvocation)
            .then().statusCode(200).body("invocationId", equalTo(scriptedInvocation.toString()))
            .body("executionCertainty", equalTo("UNKNOWN"));

        given().header("Authorization", admin(tenant)).contentType("application/json")
            .body(Map.of("reference", "ledger-" + scriptedInvocation))
            .when().post("/api/v1/investigations/" + jobId + "/reconcile").then().statusCode(200);

        // The re-run is a NEW job with a NEW key; the gateway (empty script) answers OK and the job succeeds.
        String rerunId = InvestigationTestSupport.start(analyst, breakId, null);
        assertThat(rerunId).isNotEqualTo(jobId);
        JsonPath rerun = InvestigationTestSupport.awaitTerminal(analyst, rerunId);
        assertThat(rerun.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(rerun.getString("proposalId")).isNotNull();

        List<Call> all = gateway.calls();
        assertThat(all).hasSize(2);
        assertThat(all.get(1).idempotencyKey()).isEqualTo(keyFor(rerunId)).isNotEqualTo(keyFor(jobId));
        assertThat(gatewayCalls(rerunId)).hasSize(1);
        assertThat(gatewayCalls(rerunId).get(0).get("outcome")).isEqualTo("SUCCESS");
        assertThat(gatewayCalls(jobId)).as("the old job's row is untouched").hasSize(1);
    }
}
