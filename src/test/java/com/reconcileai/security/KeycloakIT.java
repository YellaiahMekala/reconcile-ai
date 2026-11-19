// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.security;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Real identity provider: a Keycloak container imports docker/keycloak/reconcile-realm.json, the API runs in issuer
 * mode (JWKS discovery + iss + aud + tenant_id), and tokens are obtained from Keycloak exactly as a client would.
 */
class KeycloakIT extends AbstractPostgresIT {

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.0.8"))
        .withExposedPorts(8080)
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-local-only")
        .withCopyFileToContainer(MountableFile.forHostPath("docker/keycloak/reconcile-realm.json"),
            "/opt/keycloak/data/import/reconcile-realm.json")
        .withCommand("start-dev", "--import-realm")
        .waitingFor(Wait.forHttp("/realms/reconcile").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        KEYCLOAK.start();
    }

    static String base() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry r) {
        r.add("reconcile.security.issuer-uri", () -> base() + "/realms/reconcile");
        r.add("reconcile.security.audience", () -> "reconcile-api");
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    static String token(String realm, String clientId, String user, String password) {
        return given().baseUri(base()).contentType(ContentType.URLENC)
            .formParam("grant_type", "password").formParam("client_id", clientId)
            .formParam("username", user).formParam("password", password).formParam("scope", "openid")
            .when().post("/realms/" + realm + "/protocol/openid-connect/token")
            .then().statusCode(200).extract().path("access_token");
    }

    static String bearer(String user) {
        return "Bearer " + token("reconcile", "reconcile-ui", user, "demo-only");
    }

    private String create(String bearer, String ref) {
        return given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("{\"breakReference\":\"" + ref + "\",\"ledgerAmount\":\"10.00\",\"externalAmount\":\"9.00\",\"currency\":\"USD\"}")
            .when().post("/api/v1/breaks").then().statusCode(201).extract().path("id");
    }

    @Test
    void keycloakTokenCarriesTenantRolesAndAudience() {
        String raw = token("reconcile", "reconcile-ui", "analyst", "demo-only");
        String claims = new String(Base64.getUrlDecoder().decode(raw.split("\\.")[1]));
        assertThat(claims).contains("\"tenant_id\":\"TENANT_DEMO\"").contains("RECON_ANALYST").contains("reconcile-api");
    }

    @Test
    void analystFromKeycloakCanWorkAndTenantComesFromTheIdp() {
        String analyst = bearer("analyst");
        String id = create(analyst, "KC-" + UUID.randomUUID());
        given().header("Authorization", analyst).get("/api/v1/breaks/" + id).then().statusCode(200);
        assertThat(adminJdbc().queryForObject("SELECT tenant_id FROM break_item WHERE id = ?::uuid", String.class, id))
            .isEqualTo("TENANT_DEMO");

        // other tenant (from its own Keycloak user attribute) cannot see it
        given().header("Authorization", bearer("other-analyst")).get("/api/v1/breaks/" + id).then().statusCode(404);
    }

    @Test
    void rolesFromKeycloakAreEnforced() {
        given().header("Authorization", bearer("approver-l1")).contentType(ContentType.JSON)
            .body("{\"breakReference\":\"KC-X\",\"ledgerAmount\":\"1\",\"externalAmount\":\"1\",\"currency\":\"USD\"}")
            .when().post("/api/v1/breaks").then().statusCode(403);
        given().header("Authorization", bearer("sre")).get("/api/v1/audit/verify")
            .then().statusCode(200).body("valid", equalTo(true));
    }

    @Test
    void foreignOrIncompleteTokensAreRejected() {
        // user without a tenant_id attribute: validly signed by Keycloak, still 401
        given().header("Authorization", bearer("no-tenant")).get("/api/v1/breaks").then().statusCode(401);
        // token from another realm (Keycloak's own master realm): wrong issuer and no audience
        String master = token("master", "admin-cli", "admin", "admin-local-only");
        given().header("Authorization", "Bearer " + master).get("/api/v1/breaks").then().statusCode(401);
        // a dev-key token (demo persona) is not accepted once a real IdP is configured
        String devKey = JwtTestSupport.bearer("x", "TENANT_DEMO", List.of("RECON_ANALYST"));
        given().header("Authorization", devKey).get("/api/v1/breaks").then().statusCode(401);
    }
}
