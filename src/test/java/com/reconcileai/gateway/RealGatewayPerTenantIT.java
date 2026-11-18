package com.reconcileai.gateway;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in (tag {@code gateway}, free, stub provider): per-tenant gateway credentials
 * ({@code reconcile.gateway.tenants}) against the REAL platform Keycloak realm, which already holds one OAuth client
 * per tenant ({@code svc-reconcile-tenant-a} and {@code svc-reconcile-tenant-b}, each with a hard-coded
 * {@code tenant_id} claim). Start the stack with {@code platform/scripts/spikes/up.sh}. The two client secrets are read
 * only from the environment variables {@code KC_SVC_TENANT_A_SECRET} and {@code KC_SVC_TENANT_B_SECRET}.
 *
 * <p>Proof: a job of tenant A is sent with client A's token and a job of tenant B with client B's. The gateway ledger
 * scopes each invocation to the tenant in the token, so the row is visible under its own tenant's token (200) and not
 * under the other tenant's (not 200).
 */
@Tag("gateway")
@EnabledIfEnvironmentVariable(named = "KC_SVC_TENANT_A_SECRET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KC_SVC_TENANT_B_SECRET", matches = ".+")
class RealGatewayPerTenantIT extends AbstractPostgresIT {

    private static final String BASE_URL = AbstractRealGatewayIT.env("GATEWAY_BASE_URL", "http://localhost:8280");
    private static final String TOKEN_URI = AbstractRealGatewayIT.env("GATEWAY_TOKEN_URI",
        "http://localhost:8190/realms/platform/protocol/openid-connect/token");
    private static final String SCOPE = "aud-ai-gateway";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void perTenantClients(DynamicPropertyRegistry registry) {
        registry.add("reconcile.gateway.enabled", () -> "true");
        registry.add("reconcile.gateway.base-url", () -> BASE_URL);
        registry.add("reconcile.gateway.token-uri", () -> TOKEN_URI);
        registry.add("reconcile.gateway.token-scope", () -> SCOPE);
        registry.add("reconcile.gateway.tenants[tenant-a].client-id", () -> "svc-reconcile-tenant-a");
        registry.add("reconcile.gateway.tenants[tenant-a].client-secret-env", () -> "KC_SVC_TENANT_A_SECRET");
        registry.add("reconcile.gateway.tenants[tenant-b].client-id", () -> "svc-reconcile-tenant-b");
        registry.add("reconcile.gateway.tenants[tenant-b].client-secret-env", () -> "KC_SVC_TENANT_B_SECRET");
    }

    @Test
    void eachTenantsJobIsSentUnderItsOwnRealmClient() {
        RestAssured.port = port;
        String invA = invocationOf("tenant-a");
        String invB = invocationOf("tenant-b");
        assertThat(invA).isNotEqualTo(invB);

        String tokenA = token("svc-reconcile-tenant-a", "KC_SVC_TENANT_A_SECRET");
        String tokenB = token("svc-reconcile-tenant-b", "KC_SVC_TENANT_B_SECRET");

        assertThat(ledgerStatus(invA, tokenA)).as("A's invocation under A's token").isEqualTo(200);
        assertThat(ledgerStatus(invB, tokenB)).as("B's invocation under B's token").isEqualTo(200);
        assertThat(ledgerStatus(invA, tokenB)).as("A's invocation under B's token").isNotEqualTo(200);
        assertThat(ledgerStatus(invB, tokenA)).as("B's invocation under A's token").isNotEqualTo(200);
    }

    /** Runs one investigation for the tenant and returns the gateway invocation id stored for its single call. */
    private String invocationOf(String tenant) {
        String analyst = JwtTestSupport.bearer("gw-pt-" + tenant, tenant, List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        InvestigationTestSupport.awaitTerminal(analyst, jobId, Duration.ofSeconds(180));
        List<String> ids = adminJdbc().queryForList(
            "SELECT invocation_id::text FROM gateway_call WHERE job_id = ?::uuid AND invocation_id IS NOT NULL",
            String.class, jobId);
        assertThat(ids).as("the gateway returned an invocation id for " + tenant).hasSize(1);
        return ids.get(0);
    }

    private static String token(String clientId, String secretEnv) {
        Response r = given().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "client_credentials").formParam("client_id", clientId)
            .formParam("client_secret", System.getenv(secretEnv)).formParam("scope", SCOPE)
            .when().post(TOKEN_URI);
        assertThat(r.statusCode()).as("token endpoint for " + clientId).isEqualTo(200);
        return r.jsonPath().getString("access_token");
    }

    private static int ledgerStatus(String invocationId, String token) {
        return given().header("Authorization", "Bearer " + token)
            .when().get(BASE_URL + "/api/v1/invocations/" + invocationId).statusCode();
    }
}
