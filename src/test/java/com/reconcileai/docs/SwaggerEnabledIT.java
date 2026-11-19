// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.docs;

import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/** With SWAGGER_ENABLED the spec and UI load without a token and list the evidence download endpoint. */
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class SwaggerEnabledIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Test
    void specAndUiAreServed() {
        RestAssured.port = port;
        given().when().get("/v3/api-docs").then().statusCode(200)
            .body(containsString("/api/v1/breaks/{breakId}/evidence/{evidenceId}/content"))
            .body(containsString("bearer-jwt"));
        given().when().get("/swagger-ui/index.html").then().statusCode(200);
    }
}
