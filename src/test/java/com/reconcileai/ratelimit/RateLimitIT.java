// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.ratelimit;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Per-tenant throttle on POST /investigations: the third request of a burst of two is refused with 429 + Retry-After
 * before any state changes, and another tenant keeps its own bucket.
 */
@TestPropertySource(properties = {
    "reconcile.ratelimit.enabled=true", "reconcile.ratelimit.capacity=2", "reconcile.ratelimit.refill-per-minute=1"})
class RateLimitIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static ValidatableResponse startInvestigation(String bearer, String breakId) {
        return given().header("Authorization", bearer).contentType(ContentType.JSON).body("{}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations").then();
    }

    @Test
    void thirdRequestInBurstIsThrottled() {
        String a = "Bearer " + JwtTestSupport.mint("a1", "TENANT_RL_A", List.of("RECON_ANALYST"));
        // No evidence, so each allowed call is a 409. It still spends a token.
        String breakId = ApiTestSupport.createBreak(a, "100.00", "90.00");

        startInvestigation(a, breakId).statusCode(409).body("code", equalTo("EVIDENCE_NOT_READY"));
        startInvestigation(a, breakId).statusCode(409).body("code", equalTo("EVIDENCE_NOT_READY"));
        startInvestigation(a, breakId).statusCode(429)
            .header("Retry-After", notNullValue())
            .body("code", equalTo("RATE_LIMITED"));
    }

    @Test
    void otherTenantHasItsOwnBucket() {
        String a = "Bearer " + JwtTestSupport.mint("a1", "TENANT_RL_B", List.of("RECON_ANALYST"));
        String b = "Bearer " + JwtTestSupport.mint("b1", "TENANT_RL_C", List.of("RECON_ANALYST"));
        String breakA = ApiTestSupport.createBreak(a, "100.00", "90.00");
        String breakB = ApiTestSupport.createBreak(b, "100.00", "90.00");

        startInvestigation(a, breakA).statusCode(409);
        startInvestigation(a, breakA).statusCode(409);
        startInvestigation(a, breakA).statusCode(429);
        startInvestigation(b, breakB).statusCode(409);
    }
}
