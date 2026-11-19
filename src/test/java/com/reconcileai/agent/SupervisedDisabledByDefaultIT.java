// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.agent;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/** The shipped default: supervised mode is off, both variants are refused, and the other modes keep working. */
@TestPropertySource(properties = "reconcile.agent.supervised.enabled=false")
class SupervisedDisabledByDefaultIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    final String analyst = JwtTestSupport.bearer("sup-off", "TENANT_SUPERVISED_OFF", List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void supervisedModesAreRefusedWhenNotEnabledAndNothingChanges() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        for (String mode : List.of("SUPERVISED", "SUPERVISED_PLANNED")) {
            given().header("Authorization", analyst).contentType(ContentType.JSON).body("{\"mode\":\"" + mode + "\"}")
                .when().post("/api/v1/breaks/" + breakId + "/investigations")
                .then().statusCode(400).body("code", equalTo("INPUT_REJECTED"));
        }
        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().body("status", equalTo("READY_TO_INVESTIGATE"));

        // agent mode (still enabled in the test profile) works on the same break
        String job = InvestigationTestSupport.startAgent(analyst, breakId);
        assertThat(InvestigationTestSupport.awaitTerminal(analyst, job).getString("status")).isEqualTo("SUCCEEDED");
    }
}
