// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Endpoint behaviour for a single, correctly authorized tenant:
 * create/fetch, duplicate reference, validation, evidence upload + SHA-256 dedup.
 *
 * <p>Security and cross-tenant cases live in {@link SecurityAndTenantIsolationIT} so the
 * release-blocking gate is one clearly named class.
 */
class BreakAndEvidenceIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    String analystA;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        analystA = "Bearer " + JwtTestSupport.mint("a1", "TENANT_A", List.of("RECON_ANALYST"));
    }

    private String createBreak(String auth, String ref) {
        return given()
            .header("Authorization", auth).contentType(ContentType.JSON)
            .body("""
                {"breakReference":"%s","ledgerAmount":"50500.00",
                 "externalAmount":"50000.00","currency":"USD"}
                """.formatted(ref))
        .when().post("/api/v1/breaks")
        .then().statusCode(201)
            .body("variance", equalTo(500.0f))
            .body("status", equalTo("OPEN"))
            .extract().path("id");
    }

    @Test
    void createAndFetchBreak() {
        String id = createBreak(analystA, "BRK-100");
        given().header("Authorization", analystA)
        .when().get("/api/v1/breaks/" + id)
        .then().statusCode(200).body("breakReference", equalTo("BRK-100"));
    }

    @Test
    void duplicateReferenceIsRejected() {
        createBreak(analystA, "BRK-DUP");
        given().header("Authorization", analystA).contentType(ContentType.JSON)
            .body("""
                {"breakReference":"BRK-DUP","ledgerAmount":"1.00",
                 "externalAmount":"1.00","currency":"USD"}
                """)
        .when().post("/api/v1/breaks")
        .then().statusCode(409).body("code", equalTo("DUPLICATE_BREAK_REFERENCE"));
    }



    @Test
    void validationRejectsBadCurrency() {
        given().header("Authorization", analystA).contentType(ContentType.JSON)
            .body("""
                {"breakReference":"BRK-BAD","ledgerAmount":"1.00",
                 "externalAmount":"1.00","currency":"usd"}
                """)
        .when().post("/api/v1/breaks")
        .then().statusCode(400).body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void evidenceUploadThenDedup() {
        String id = createBreak(analystA, "BRK-EV");
        byte[] file = "statement line: settled 50000".getBytes();

        // first upload -> 201, PROCESSING
        given().header("Authorization", analystA)
            .multiPart("file", "stmt.txt", file, "text/plain")
        .when().post("/api/v1/breaks/" + id + "/evidence")
        .then().statusCode(201)
            .body("status", equalTo("READY"))
            .body("chunkCount", greaterThan(0))
            .body("deduplicated", equalTo(false))
            .body("contentHash", notNullValue());

        // identical file -> 200, deduplicated
        given().header("Authorization", analystA)
            .multiPart("file", "stmt.txt", file, "text/plain")
        .when().post("/api/v1/breaks/" + id + "/evidence")
        .then().statusCode(200).body("deduplicated", equalTo(true));

        // list shows exactly one
        given().header("Authorization", analystA)
        .when().get("/api/v1/breaks/" + id + "/evidence")
        .then().statusCode(200).body("size()", equalTo(1));
    }

}
