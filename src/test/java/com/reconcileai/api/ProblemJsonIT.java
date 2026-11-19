// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Platform D1.1.4: every error is RFC 7807 {@code application/problem+json} with type, title, status, detail and
 * instance, plus the stable {@code code} extension (values and HTTP statuses unchanged). 401/403 from the security
 * filter chain too, with the bearer {@code WWW-Authenticate} header kept.
 */
class ProblemJsonIT extends AbstractPostgresIT {

    private static final String PROBLEM = "application/problem+json";

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static String analyst() {
        return JwtTestSupport.bearer("pj-analyst", "TENANT_PJ", List.of("RECON_ANALYST"));
    }

    @Test
    void notFoundIsAProblemWithTheExistingCode() {
        String path = "/api/v1/breaks/" + UUID.randomUUID();
        given().header("Authorization", analyst()).get(path)
            .then().statusCode(404).contentType(PROBLEM)
            .body("type", startsWith("urn:reconcile-ai:problem:"))
            .body("title", equalTo("Not Found"))
            .body("status", equalTo(404))
            .body("detail", notNullValue())
            .body("instance", equalTo(path))
            .body("code", equalTo("BREAK_NOT_FOUND"))
            .body("message", notNullValue());
    }

    @Test
    void validationFailureIsAProblem() {
        given().header("Authorization", analyst()).contentType(ContentType.JSON).body("{}").post("/api/v1/breaks")
            .then().statusCode(400).contentType(PROBLEM)
            .body("status", equalTo(400))
            .body("code", equalTo("VALIDATION_FAILED"))
            .body("type", equalTo("urn:reconcile-ai:problem:validation-failed"));
    }

    @Test
    void malformedJsonIsAProblem() {
        given().header("Authorization", analyst()).contentType(ContentType.JSON).body("{not json").post("/api/v1/breaks")
            .then().statusCode(400).contentType(PROBLEM).body("code", equalTo("MALFORMED_REQUEST"));
    }

    @Test
    void missingTokenIsA401ProblemAndKeepsWwwAuthenticate() {
        given().get("/api/v1/breaks")
            .then().statusCode(401).contentType(PROBLEM)
            .header("WWW-Authenticate", startsWith("Bearer"))
            .body("status", equalTo(401))
            .body("code", equalTo("UNAUTHORIZED"))
            .body("instance", equalTo("/api/v1/breaks"));
    }

    @Test
    void invalidTokenIsA401Problem() {
        given().header("Authorization", "Bearer not-a-jwt").get("/api/v1/breaks")
            .then().statusCode(401).contentType(PROBLEM)
            .header("WWW-Authenticate", containsString("invalid_token"))
            .body("code", equalTo("UNAUTHORIZED"));
    }

    @Test
    void wrongRoleIsA403Problem() {
        String approver = JwtTestSupport.bearer("pj-approver", "TENANT_PJ", List.of("APPROVER_L1"));
        given().header("Authorization", approver).contentType(ContentType.JSON)
            .body("{\"breakReference\":\"PJ-1\",\"ledgerAmount\":\"1.00\",\"externalAmount\":\"1.00\",\"currency\":\"USD\"}")
            .post("/api/v1/breaks")
            .then().statusCode(403).contentType(PROBLEM)
            .body("status", equalTo(403))
            .body("code", equalTo("FORBIDDEN"));
    }
}
