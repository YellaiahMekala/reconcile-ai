// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.gateway;

import com.reconcileai.application.investigation.InvestigationRecovery;
import com.reconcileai.support.AbstractGatewayIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Fixes from the gateway-route security review:
 * <ol>
 *   <li>agent and supervised modes would call the model directly and skip the gateway, so they are refused while the
 *       route is on;</li>
 *   <li>a node that stalls past the lease must not overwrite the failure recovery already recorded;</li>
 *   <li>recovery flags an unknown gateway outcome only for jobs that use the gateway (SINGLE_CALL).</li>
 * </ol>
 */
class GatewayReviewFixesIT extends AbstractGatewayIT {

    @Autowired
    InvestigationRecovery recovery;

    @ParameterizedTest
    @ValueSource(strings = {"AGENT", "SUPERVISED", "SUPERVISED_PLANNED"})
    void ungovernedModesAreRefusedWhileTheGatewayRouteIsOn(String mode) {
        String analyst = analyst("TENANT_GW_MODE");
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");

        given().header("Authorization", analyst).contentType("application/json").body("{\"mode\":\"" + mode + "\"}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(409).body("code", equalTo("MODE_NOT_GOVERNED"));

        assertThat(gateway.calls()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation_job WHERE break_item_id = ?::uuid",
            Integer.class, breakId)).isZero();
    }

    @Test
    void aLateResultFromAStalledNodeDoesNotUndoRecovery() throws Exception {
        String tenant = "TENANT_GW_STALL";
        String analyst = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gateway.holdNextChat(entered, release);

        String jobId;
        try {
            jobId = InvestigationTestSupport.start(analyst, breakId, null);
            assertThat(entered.await(30, TimeUnit.SECONDS)).as("the call reached the gateway").isTrue();

            // The node goes quiet past the lease; another instance's sweep treats the job as orphaned mid-call.
            jdbc.update("UPDATE investigation_job SET heartbeat_at = now() - interval '1 hour' WHERE id = ?::uuid", jobId);
            recovery.sweep();
            Map<String, Object> failed = jobRow(jobId);
            assertThat(failed.get("status")).isEqualTo("FAILED");
            assertThat(failed.get("outcome_unknown")).isEqualTo(Boolean.TRUE);
        } finally {
            release.countDown();
        }

        // The stalled node wakes up with a good answer. It is recorded as spend, but it must not revive the job.
        await().atMost(30, TimeUnit.SECONDS).until(() -> !gatewayCalls(jobId).isEmpty());
        Thread.sleep(1500); // let the late result run through verify/route/persist
        Map<String, Object> row = jobRow(jobId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("outcome_unknown")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("proposal_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT stage FROM investigation_job WHERE id = ?::uuid", String.class, jobId))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolution_proposal WHERE break_item_id = ?::uuid",
            Integer.class, breakId)).isZero();
        assertThat(breakStatus(analyst, breakId)).isEqualTo("FAILED_RETRYABLE");
    }

    @Test
    void recoveryFlagsUnknownOutcomeOnlyForGatewayJobs() {
        String tenant = "TENANT_GW_RECOVER";
        String analyst = analyst(tenant);
        UUID single = orphan(tenant, ApiTestSupport.createBreak(analyst, "10.00", "9.00"), "SINGLE_CALL");
        UUID agent = orphan(tenant, ApiTestSupport.createBreak(analyst, "10.00", "9.00"), "AGENT");

        recovery.sweep();

        assertThat(jobRow(single.toString()).get("outcome_unknown")).isEqualTo(Boolean.TRUE);
        assertThat(jobRow(agent.toString()).get("outcome_unknown")).isEqualTo(Boolean.FALSE);
    }

    private UUID orphan(String tenant, String breakId, String mode) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("UPDATE break_item SET status = 'INVESTIGATING' WHERE id = ?::uuid", breakId);
        jdbc.update("""
            INSERT INTO investigation_job (id, tenant_id, break_item_id, status, stage, mode, initiated_by, owner_node,
                                           heartbeat_at, created_at, started_at, version)
            VALUES (?, ?, ?::uuid, 'RUNNING', 'CALLING_MODEL', ?, 'm1', 'dead-node',
                    now() - interval '1 hour', now() - interval '1 hour', now() - interval '1 hour', 1)""",
            jobId, tenant, breakId, mode);
        return jobId;
    }
}
