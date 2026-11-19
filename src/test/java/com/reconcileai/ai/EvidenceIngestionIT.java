// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.ai;

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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Evidence reaches READY, chunks land in vector_store with the
 * metadata contract, dedup skips re-embedding, bad input fails cleanly, break readiness is derived.
 */
class EvidenceIngestionIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

        final JdbcTemplate jdbc = adminJdbc();

    final String analyst = JwtTestSupport.bearer("a1", "TENANT_ING", List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private int chunkRows(String breakId) {
        return jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'breakItemId' = ?", Integer.class, breakId);
    }

    @Test
    void textEvidenceBecomesReadyWithTenantMetadata() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        String status = ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        assertThat(status).isEqualTo("READY");
        assertThat(chunkRows(breakId)).isPositive();
        String tenant = jdbc.queryForObject(
            "SELECT DISTINCT metadata->>'tenantId' FROM vector_store WHERE metadata->>'breakItemId' = ?", String.class, breakId);
        assertThat(tenant).isEqualTo("TENANT_ING");

        // Break readiness derived from evidence statuses
        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().statusCode(200).body("status", equalTo("READY_TO_INVESTIGATE"));
    }

    @Test
    void pdfEvidenceIsParsedByTika() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        String status = ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/fee-advice.pdf", "application/pdf");

        assertThat(status).isEqualTo("READY");
        String content = jdbc.queryForObject(
            "SELECT string_agg(content, ' ') FROM vector_store WHERE metadata->>'breakItemId' = ?", String.class, breakId);
        assertThat(content).contains("INV-20931");
    }

    @Test
    void duplicateUploadDoesNotReEmbed() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        int before = chunkRows(breakId);

        given().header("Authorization", analyst)
            .multiPart("file", "copy.txt", ApiTestSupport.fixtureBytes("/fixtures/evidence/bank-statement-timing.txt"), "text/plain")
        .when().post("/api/v1/breaks/" + breakId + "/evidence")
        .then().statusCode(200).body("deduplicated", equalTo(true));

        assertThat(chunkRows(breakId)).isEqualTo(before);
    }

    @Test
    void emptyTextFailsCleanlyAndBreakStaysOpen() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        given().header("Authorization", analyst)
            .multiPart("file", "blank.txt", "   \n  ".getBytes(), "text/plain")
        .when().post("/api/v1/breaks/" + breakId + "/evidence")
        .then().statusCode(201)
            .body("status", equalTo("FAILED"))
            .body("failureReason", equalTo("NO_EXTRACTABLE_TEXT"));

        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().body("status", equalTo("OPEN"));
    }

    @Test
    void chunkIdsAreDeterministicPerEvidence() {
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        String evidenceId = given().header("Authorization", analyst)
            .multiPart("file", "s.txt", ApiTestSupport.fixtureBytes("/fixtures/evidence/bank-statement-timing.txt"), "text/plain")
        .when().post("/api/v1/breaks/" + breakId + "/evidence")
        .then().statusCode(201).body("chunkCount", greaterThan(0)).extract().path("id");

        String expected = com.reconcileai.infrastructure.ai.ingestion.EvidenceIngestor.chunkId(UUID.fromString(evidenceId), 0);
        Integer found = jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE id = ?::uuid", Integer.class, expected);
        assertThat(found).isEqualTo(1);
    }

    @Test
    void fullTextColumnExists() {
        Integer cols = jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_name = 'vector_store' AND column_name = 'content_tsv'""", Integer.class);
        assertThat(cols).isEqualTo(1);
    }
}
