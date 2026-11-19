// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.gateway;

import com.reconcileai.application.investigation.InvestigationRecovery;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Platform Q2.3: a break whose last gateway call may have run (and been billed) cannot be re-investigated until
 * someone other than the starter has checked the gateway ledger and recorded a reference.
 */
class GatewayOutcomeGuardIT extends AbstractPostgresIT {

    private static final String TENANT = "TENANT_GUARD";

    @LocalServerPort
    int port;

    final JdbcTemplate jdbc = adminJdbc();

    @Autowired
    InvestigationRecovery recovery;

    final String analyst = JwtTestSupport.bearer("m1", TENANT, List.of("RECON_ANALYST"));
    final String sameUserAdmin = JwtTestSupport.bearer("m1", TENANT, List.of("SRE_ADMIN"));
    final String otherAdmin = JwtTestSupport.bearer("m2", TENANT, List.of("SRE_ADMIN"));
    final String l2 = JwtTestSupport.bearer("m3", TENANT, List.of("APPROVER_L2"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private UUID failedJob(String breakId, String reason, boolean outcomeUnknown) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("UPDATE break_item SET status = 'FAILED_RETRYABLE' WHERE id = ?::uuid", breakId);
        jdbc.update("""
            INSERT INTO investigation_job (id, tenant_id, break_item_id, status, stage, initiated_by, failure_reason,
                                           outcome_unknown, created_at, finished_at, version)
            VALUES (?, ?, ?::uuid, 'FAILED', 'FAILED', 'm1', ?, ?, now(), now(), 1)""",
            jobId, TENANT, breakId, reason, outcomeUnknown);
        return jobId;
    }

    private ValidatableResponse start(String breakId) {
        return given().header("Authorization", analyst).contentType("application/json").body("{}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations").then();
    }

    private ValidatableResponse reconcile(String bearer, UUID jobId, String reference) {
        return given().header("Authorization", bearer).contentType("application/json")
            .body(Map.of("reference", reference))
            .when().post("/api/v1/investigations/" + jobId + "/reconcile").then();
    }

    @Test
    void unknownOutcomeBlocksARerunUntilReconciledByAnotherPerson() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        UUID jobId = failedJob(breakId, "GATEWAY_UNKNOWN_OUTCOME", true);

        start(breakId).statusCode(409).body("code", equalTo("OUTCOME_UNRECONCILED"));

        // The analyst role cannot reconcile; the starter with another role cannot either.
        reconcile(analyst, jobId, "ticket-123").statusCode(403);
        reconcile(sameUserAdmin, jobId, "ticket-123").statusCode(409).body("code", equalTo("RECONCILER_MUST_DIFFER"));
        start(breakId).statusCode(409).body("code", equalTo("OUTCOME_UNRECONCILED"));

        reconcile(otherAdmin, jobId, "9f1c2d3e-0000-4000-8000-000000000001").statusCode(200);

        // No longer blocked by the guard; this break has no evidence, so the ordinary check answers instead.
        start(breakId).statusCode(409).body("code", equalTo("EVIDENCE_NOT_READY"));
        assertThat(jdbc.queryForObject("SELECT reconciled_by FROM investigation_job WHERE id = ?", String.class, jobId))
            .isEqualTo("m2");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE event_type = 'INVESTIGATION_OUTCOME_RECONCILED' AND tenant_id = ?",
            Integer.class, TENANT)).isGreaterThanOrEqualTo(1);

        // A second reconcile of the same job is refused.
        reconcile(l2, jobId, "ticket-456").statusCode(409).body("code", equalTo("NOT_AWAITING_RECONCILIATION"));
    }

    @Test
    void referenceMustBeAnIdentifierNotFreeText() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        UUID jobId = failedJob(breakId, "GATEWAY_UNKNOWN_OUTCOME", true);

        reconcile(otherAdmin, jobId, "looked at it, seems fine").statusCode(400);
        reconcile(otherAdmin, jobId, "ab").statusCode(400);
        start(breakId).statusCode(409).body("code", equalTo("OUTCOME_UNRECONCILED"));
    }

    @Test
    void aFailureWhereNothingWasSentDoesNotBlockARerun() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        UUID jobId = failedJob(breakId, "GATEWAY_BUDGET_BLOCKED", false);

        start(breakId).statusCode(409).body("code", equalTo("EVIDENCE_NOT_READY"));
        reconcile(otherAdmin, jobId, "ticket-123").statusCode(409).body("code", equalTo("NOT_AWAITING_RECONCILIATION"));
    }

    @Test
    void anotherTenantsJobIs404() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        UUID jobId = failedJob(breakId, "GATEWAY_UNKNOWN_OUTCOME", true);
        String foreignAdmin = JwtTestSupport.bearer("x1", "TENANT_OTHER", List.of("SRE_ADMIN"));

        reconcile(foreignAdmin, jobId, "ticket-123").statusCode(404);
    }

    @Test
    void recoveryWithoutTheGatewayRouteDoesNotFlagAnInterruptedJob() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        UUID jobId = UUID.randomUUID();
        jdbc.update("UPDATE break_item SET status = 'INVESTIGATING' WHERE id = ?::uuid", breakId);
        jdbc.update("""
            INSERT INTO investigation_job (id, tenant_id, break_item_id, status, stage, initiated_by, owner_node,
                                           heartbeat_at, created_at, started_at, version)
            VALUES (?, ?, ?::uuid, 'RUNNING', 'CALLING_MODEL', 'm1', 'dead-node',
                    now() - interval '1 hour', now() - interval '1 hour', now() - interval '1 hour', 1)""",
            jobId, TENANT, breakId);

        recovery.sweep();

        assertThat(jdbc.queryForObject("SELECT outcome_unknown FROM investigation_job WHERE id = ?", Boolean.class, jobId))
            .isFalse();
        start(breakId).statusCode(409).body("code", equalTo("EVIDENCE_NOT_READY"));
    }
}
