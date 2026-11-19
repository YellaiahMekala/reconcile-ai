// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.digest;

import com.reconcileai.application.digest.DailyDigestJob;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.reconcileai.support.ApprovalTestSupport.decide;
import static com.reconcileai.support.ApprovalTestSupport.taskIdForProposal;
import static com.reconcileai.support.InvestigationTestSupport.investigate;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Daily digest: numbers match what happened, tenant-isolated, idempotent, single-instance. */
class DailyDigestIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    DailyDigestJob job;

    final String tenant = "TENANT_DIGEST_" + UUID.randomUUID().toString().substring(0, 6);
    final String analyst = JwtTestSupport.bearer("dg-analyst", tenant, List.of("RECON_ANALYST"));
    final String approver = JwtTestSupport.bearer("dg-l1", tenant, List.of("APPROVER_L1"));
    final String sre = JwtTestSupport.bearer("dg-sre", tenant, List.of("SRE_ADMIN"));
    final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void digestReflectsTheDaysActivityAndIsReadableOnlyByItsTenant() {
        // one break resolved (L1), one break left open in manual review
        JsonPath p1 = investigate(analyst, "50500.00", "50000.00", "/fixtures/evidence/bank-statement-timing.txt");
        String task = taskIdForProposal(approver, p1.getString("id")).orElseThrow();
        decide(approver, task, "APPROVE", "fee confirmed").statusCode(200);
        investigate(analyst, "120.00", "100.00", "/fixtures/evidence/vague-note.txt");

        assertThat(job.runFor(today)).isGreaterThanOrEqualTo(1);

        JsonPath d = given().header("Authorization", approver).get("/api/v1/digests/" + today)
            .then().statusCode(200).extract().jsonPath();
        assertThat(d.getInt("summary.breaksCreated")).isEqualTo(2);
        assertThat(d.getInt("summary.proposalsRouted")).isEqualTo(2);
        assertThat(d.getInt("summary.resolved")).isEqualTo(1);
        assertThat(d.getInt("summary.openTotal")).isEqualTo(1);
        assertThat(d.getInt("summary.routes.L1_APPROVAL")).isEqualTo(1);
        assertThat(d.getInt("summary.routes.MANUAL_REVIEW")).isEqualTo(1);
        assertThat(d.getInt("summary.rootCauses.FEE_OR_CHARGE")).isEqualTo(1);
        assertThat(d.getInt("summary.modelCalls")).isEqualTo(2);
        assertThat(d.getString("narrative")).contains("2 breaks created").contains("1 resolved");

        // idempotent: a second run overwrites, never duplicates
        job.runFor(today);
        assertThat(adminJdbc().queryForObject("SELECT count(*) FROM daily_digest WHERE tenant_id = ? AND digest_date = ?",
            Long.class, tenant, today)).isEqualTo(1);

        // ready event goes to the outbox in the same transaction
        assertThat(adminJdbc().queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'DAILY_DIGEST_READY'",
            Long.class, tenant)).isEqualTo(2);

        // access: analyst 403; another tenant's SRE sees 404 for the same day
        given().header("Authorization", analyst).get("/api/v1/digests/" + today).then().statusCode(403);
        String otherSre = JwtTestSupport.bearer("dg-other", "TENANT_DIGEST_NONE", List.of("SRE_ADMIN"));
        given().header("Authorization", otherSre).get("/api/v1/digests/" + today).then().statusCode(404);
        given().header("Authorization", sre).get("/api/v1/digests?days=3").then().statusCode(200);
    }

    @Test
    void onlyOneInstanceRunsTheJobAtATime() {
        // Another "instance" holds the advisory lock on its own session.
        Integer result = adminJdbc().execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_lock(7220002)")) {
                ps.execute();
            }
            try {
                return job.runFor(today);
            } finally {
                try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_unlock(7220002)")) {
                    ps.execute();
                }
            }
        });
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void sreCanBackfillAPastDayButNotTheFuture() {
        given().header("Authorization", sre).post("/api/v1/digests/" + today.minusDays(3) + "/generate")
            .then().statusCode(200);
        given().header("Authorization", sre).post("/api/v1/digests/" + today.plusDays(2) + "/generate")
            .then().statusCode(400);
        given().header("Authorization", approver).post("/api/v1/digests/" + today.minusDays(3) + "/generate")
            .then().statusCode(403);
    }
}
