// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.rero;

import com.reconcileai.application.investigation.InvestigationRecovery;
import com.reconcileai.application.rero.ReroClientPort;
import com.reconcileai.support.AbstractReroIT;
import com.reconcileai.support.FakeReroServer.Scenario;
import com.reconcileai.support.FakeReroServer.Start;
import com.reconcileai.support.InvestigationTestSupport;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Platform Q3.5 end to end with a fake RERO that really calls the internal API with the run token it was given.
 * RERO only ever supplies policy-gate inputs: routes, approval tasks and break statuses are reconcile-ai's.
 */
class ReroInvestigationIT extends AbstractReroIT {

    private static final String FIXTURE = "/fixtures/evidence/fee-reviewer-challenge.txt";

    @Autowired
    ReroClientPort client;

    @Autowired
    InvestigationRecovery recovery;

    private JsonPath investigate(String tenant, Scenario scenario, String note) {
        rero.scenario(scenario);
        String bearer = analyst(tenant);
        String breakId = breakWithEvidence(tenant, FIXTURE);
        String jobId = InvestigationTestSupport.startMode(bearer, breakId, "RERO", note);
        return InvestigationTestSupport.awaitTerminal(bearer, jobId, Duration.ofSeconds(30));
    }

    private Map<String, Object> jobRow(String jobId) {
        return jdbc.queryForMap("SELECT j.status, j.failure_reason, j.outcome_unknown, j.review_verdict, j.rero_run_id, "
            + "j.rero_gateway_invocation_ids, j.proposal_id, j.tool_calls_used, b.status AS break_status "
            + "FROM investigation_job j JOIN break_item b ON b.id = j.break_item_id WHERE j.id = ?::uuid", jobId);
    }

    private int proposals(String jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM resolution_proposal WHERE job_id = ?::uuid", Integer.class, jobId);
    }

    @Test
    void passBecomesAnOrdinaryProposalWithAnApprovalTask() {
        JsonPath job = investigate(TENANT_A, Scenario.PASS, "Check fee, contact ops.lead@example.com");
        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        String jobId = job.getString("id");

        Map<String, Object> row = jobRow(jobId);
        assertThat(row.get("review_verdict")).isEqualTo("PASS");
        assertThat(row.get("rero_run_id")).isNotNull();
        assertThat(String.valueOf(row.get("rero_gateway_invocation_ids"))).contains(",");
        assertThat(row.get("break_status")).isEqualTo("PENDING_L1");
        assertThat((Integer) row.get("tool_calls_used")).isEqualTo(2);

        JsonPath proposal = InvestigationTestSupport.proposal(analyst(TENANT_A), job.getString("proposalId"));
        assertThat(proposal.getString("route")).isEqualTo("L1_APPROVAL");
        assertThat(proposal.getString("rootCause")).isEqualTo("FEE_OR_CHARGE");
        assertThat(proposal.getBoolean("citationsValid")).isTrue();
        List<String> served = jdbc.queryForList("SELECT chunk_id FROM rero_served_chunk WHERE job_id = ?::uuid",
            String.class, jobId);
        assertThat(served).containsAll(proposal.getList("citedChunkIds", String.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_task WHERE proposal_id = ?::uuid", Integer.class,
            job.getString("proposalId"))).isEqualTo(1);

        // Contract C start request: idempotency key = jobId, run token present, no tenant, masked note, fixed limits.
        Start start = rero.starts().get(0);
        assertThat(start.tenant()).isEqualTo(TENANT_A);
        assertThat(start.idempotencyKey()).isEqualTo(jobId);
        assertThat(start.hadRunToken()).isTrue();
        assertThat(start.body().has("tenantId")).isFalse();
        assertThat(start.body().path("analystNote").asText()).doesNotContain("ops.lead@example.com");
        assertThat(start.body().path("limits").path("maxToolCalls").asInt()).isEqualTo(8);
        assertThat(start.body().path("limits").path("deadlineSeconds").asInt()).isEqualTo(5);
        assertThat(rero.internalStatuses()).containsOnly(200);
        // Never in the database: the run token.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE details LIKE '%eyJ%'", Integer.class))
            .isZero();
    }

    @Test
    void challengeGoesToManualReviewWithoutAnApprovalTask() {
        JsonPath job = investigate(TENANT_A, Scenario.CHALLENGE, null);
        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        JsonPath proposal = InvestigationTestSupport.proposal(analyst(TENANT_A), job.getString("proposalId"));
        assertThat(proposal.getString("route")).isEqualTo("MANUAL_REVIEW");
        assertThat(proposal.getString("routeReason")).isEqualTo("REVIEWER_CHALLENGE");
        assertThat(jobRow(job.getString("id")).get("review_verdict")).isEqualTo("CHALLENGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_task WHERE proposal_id = ?::uuid", Integer.class,
            job.getString("proposalId"))).isZero();
    }

    @Test
    void aCitationWeNeverServedFailsTheJobWithoutAProposal() {
        JsonPath job = investigate(TENANT_A, Scenario.INVALID_CITATION, null);
        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("RERO_INVALID_CITATION");
        assertThat(proposals(job.getString("id"))).isZero();
        assertThat(jobRow(job.getString("id")).get("break_status")).isEqualTo("FAILED_RETRYABLE");
    }

    @Test
    void aKnowledgeReferenceIsNeverCitable() {
        JsonPath job = investigate(TENANT_A, Scenario.KB_CITATION, null);
        assertThat(job.getString("failureReason")).isEqualTo("RERO_INVALID_CITATION");
        assertThat(proposals(job.getString("id"))).isZero();
    }

    @Test
    void aFailedRunIsARetryableFailure() {
        JsonPath job = investigate(TENANT_A, Scenario.FAILED_MODEL_UNAVAILABLE, null);
        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("RERO_MODEL_UNAVAILABLE");
        Map<String, Object> row = jobRow(job.getString("id"));
        assertThat(row.get("outcome_unknown")).isEqualTo(false);
        assertThat(row.get("break_status")).isEqualTo("FAILED_RETRYABLE");
        assertThat(row.get("rero_gateway_invocation_ids")).isNotNull();
        assertThat(proposals(job.getString("id"))).isZero();
    }

    @Test
    void reroDownIsARetryableFailureAndTheBreakCanBeReinvestigated() {
        JsonPath job = investigate(TENANT_A, Scenario.DOWN, null);
        assertThat(job.getString("failureReason")).isEqualTo("RERO_UNAVAILABLE");
        String breakId = job.getString("breakItemId");
        assertThat(jobRow(job.getString("id")).get("break_status")).isEqualTo("FAILED_RETRYABLE");

        rero.scenario(Scenario.PASS);
        String again = InvestigationTestSupport.startMode(analyst(TENANT_A), breakId, "RERO", null);
        JsonPath second = InvestigationTestSupport.awaitTerminal(analyst(TENANT_A), again, Duration.ofSeconds(30));
        assertThat(second.getString("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void aHangingRunHitsOurDeadlineAndItsTokenStopsWorking() {
        JsonPath job = investigate(TENANT_A, Scenario.HANG, null);
        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("RERO_DEADLINE_EXCEEDED");
        assertThat(proposals(job.getString("id"))).isZero();
    }

    @Test
    void restartingTheSameJobReusesTheRunAndWritesNothingTwice() {
        JsonPath job = investigate(TENANT_A, Scenario.PASS, null);
        UUID jobId = UUID.fromString(job.getString("id"));
        assertThat(rero.runsCreated()).isEqualTo(1);

        // A retried start for the same job (same Idempotency-Key) returns the existing run, no second run.
        ReroClientPort.ReroRun replay = client.start(TENANT_A, new ReroClientPort.StartRequest(jobId, "",
            new ReroClientPort.Limits(8, 2, 5), com.reconcileai.support.RunTokenTestKeys.sign(TENANT_A, jobId)));
        assertThat(replay.runId().toString()).isEqualTo(jobRow(jobId.toString()).get("rero_run_id").toString());
        assertThat(rero.runsCreated()).isEqualTo(1);
        assertThat(proposals(jobId.toString())).isEqualTo(1);
    }

    @Test
    void anotherTenantCannotPollTheRun() {
        JsonPath job = investigate(TENANT_A, Scenario.PASS, null);
        UUID runId = UUID.fromString(jobRow(job.getString("id")).get("rero_run_id").toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.get(TENANT_B, runId))
            .hasMessage("RERO_INVALID_RESPONSE"); // RERO answers 404 for another tenant's run
    }

    @Test
    void anOrphanedReroJobFailsRetryablyWithoutAnUnknownOutcomeFlag() {
        UUID jobId = runningJob(TENANT_A, "RERO", 8);
        jdbc.update("UPDATE investigation_job SET heartbeat_at = now() - interval '1 hour' WHERE id = ?", jobId);
        jdbc.update("UPDATE break_item SET status = 'INVESTIGATING' WHERE id = "
            + "(SELECT break_item_id FROM investigation_job WHERE id = ?)", jobId);

        recovery.sweep();

        Map<String, Object> row = jobRow(jobId.toString());
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("INTERRUPTED");
        assertThat(row.get("outcome_unknown")).isEqualTo(false);
        assertThat(row.get("break_status")).isEqualTo("FAILED_RETRYABLE");
    }

    @Test
    void theServiceTokenMustCarryBothAudiences() {
        rero.singleAudience(true);
        try {
            JsonPath job = investigate(TENANT_C, Scenario.PASS, null);
            assertThat(job.getString("failureReason")).isEqualTo("RERO_TOKEN_UNAVAILABLE");
            assertThat(rero.starts()).isEmpty(); // nothing was sent
        } finally {
            rero.singleAudience(false);
        }
    }

    @Test
    void aTenantWithoutAReroClientSendsNothing() {
        rero.scenario(Scenario.PASS);
        String tenant = "TENANT_RNONE";
        String bearer = analyst(tenant);
        String breakId = breakWithEvidence(tenant, FIXTURE);
        String jobId = InvestigationTestSupport.startMode(bearer, breakId, "RERO", null);
        JsonPath job = InvestigationTestSupport.awaitTerminal(bearer, jobId, Duration.ofSeconds(30));
        assertThat(job.getString("failureReason")).isEqualTo("RERO_TOKEN_UNAVAILABLE");
        assertThat(rero.starts()).isEmpty();
    }

    @Test
    void theJobResponseStillNeverResolves() {
        JsonPath job = investigate(TENANT_A, Scenario.PASS, null);
        given().header("Authorization", analyst(TENANT_A)).when().get("/api/v1/breaks/" + job.getString("breakItemId"))
            .then().statusCode(200).body("status", equalTo("PENDING_L1"));
    }
}
