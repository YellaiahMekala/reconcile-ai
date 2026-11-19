// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.investigation;

import com.reconcileai.application.investigation.InvestigationRecovery;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Simulates a node that died mid-investigation: RUNNING job with a heartbeat far older than the lease. */
class InvestigationRecoveryIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

        final JdbcTemplate jdbc = adminJdbc();

    @Autowired
    InvestigationRecovery recovery;

    final String analyst = JwtTestSupport.bearer("m1", "TENANT_REC", List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void orphanedRunningJobIsFailedAndBreakBecomesRetryable() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        UUID jobId = UUID.randomUUID();
        jdbc.update("UPDATE break_item SET status = 'INVESTIGATING' WHERE id = ?::uuid", breakId);
        jdbc.update("""
            INSERT INTO investigation_job (id, tenant_id, break_item_id, status, stage, initiated_by, owner_node,
                                           heartbeat_at, created_at, started_at, version)
            VALUES (?, 'TENANT_REC', ?::uuid, 'RUNNING', 'CALLING_MODEL', 'm1', 'dead-node',
                    now() - interval '1 hour', now() - interval '1 hour', now() - interval '1 hour', 1)""",
            jobId, breakId);

        assertThat(recovery.sweep()).isGreaterThanOrEqualTo(1);

        given().header("Authorization", analyst).when().get("/api/v1/investigations/" + jobId)
            .then().statusCode(200)
            .body("status", org.hamcrest.Matchers.equalTo("FAILED"))
            .body("failureReason", org.hamcrest.Matchers.equalTo("INTERRUPTED"));
        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().body("status", org.hamcrest.Matchers.equalTo("FAILED_RETRYABLE"));
    }
}
