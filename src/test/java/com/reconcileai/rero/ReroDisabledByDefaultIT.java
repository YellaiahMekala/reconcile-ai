// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.rero;

import com.reconcileai.application.rero.ReroInvestigation;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import com.reconcileai.support.RunTokenTestKeys;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/** Default build (no RERO properties): the mode is refused, nothing is wired, the internal API accepts no token. */
class ReroDisabledByDefaultIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    ObjectProvider<ReroInvestigation> rero;

    @BeforeEach
    void port() {
        RestAssured.port = port;
    }

    @Test
    void reroModeIsRefusedWith409() {
        String analyst = JwtTestSupport.bearer("rero-off", "TENANT_RERO_OFF", List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        given().header("Authorization", analyst).contentType("application/json").body("{\"mode\":\"RERO\"}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(409).body("code", equalTo("RERO_DISABLED"));
        assertThat(rero.getIfAvailable()).isNull();
    }

    @Test
    void theInternalApiRejectsEverythingWithoutKeys() {
        UUID job = UUID.randomUUID();
        // Even a well-formed token signed with some key: no verification key is configured, so nothing passes.
        given().header("Authorization", "Bearer " + RunTokenTestKeys.sign("TENANT_RERO_OFF", job))
            .when().get("/internal/rero/v1/jobs/" + job + "/break")
            .then().statusCode(401).body("code", equalTo("RUN_TOKEN_INVALID"));
        given().when().post("/internal/rero/v1/jobs/" + job + "/break").then().statusCode(405);
    }
}
