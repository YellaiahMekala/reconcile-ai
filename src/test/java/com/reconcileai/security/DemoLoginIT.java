// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.security;

import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/** demo-login profile: persona tokens go through the normal JWT validation and role rules. */
@ActiveProfiles({"test", "mock-llm", "demo-login"})
@TestPropertySource(properties = "reconcile.demo-login.private-key-location=classpath:jwt/dev-private-pkcs8.pem")
class DemoLoginIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private String token(String persona) {
        return given().contentType(ContentType.JSON).body("{\"persona\":\"" + persona + "\"}")
            .when().post("/dev/token").then().statusCode(200).extract().path("token");
    }

    @Test
    void personasAreListed() {
        given().when().get("/dev/personas").then().statusCode(200)
            .body("id", hasItems("analyst", "approver-l1", "approver-l2", "sre", "other-tenant"));
    }

    @Test
    void analystTokenWorksAndRolesAreEnforced() {
        String analyst = token("analyst");
        given().header("Authorization", "Bearer " + analyst).when().get("/api/v1/breaks").then().statusCode(200);
        given().header("Authorization", "Bearer " + analyst).when().get("/api/v1/audit/verify").then().statusCode(403);

        String sre = token("sre");
        given().header("Authorization", "Bearer " + sre).when().get("/api/v1/audit/verify")
            .then().statusCode(200).body("valid", equalTo(true));
    }

    @Test
    void unknownPersonaIsRejected() {
        given().contentType(ContentType.JSON).body("{\"persona\":\"root\"}").when().post("/dev/token")
            .then().statusCode(400);
    }
}
