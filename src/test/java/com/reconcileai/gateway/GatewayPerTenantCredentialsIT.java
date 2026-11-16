package com.reconcileai.gateway;

import com.reconcileai.support.AbstractGatewayIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.FakeGatewayServer.Call;
import com.reconcileai.support.FakeGatewayServer.Scripted;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Per-tenant gateway credentials ({@code reconcile.gateway.tenants}), end to end: each job's call carries a token of
 * its own tenant's OAuth client, so the gateway bills and scopes its ledger by the right tenant. A tenant with no
 * client, or a client whose token claims another tenant, is refused before anything reaches the gateway.
 */
class GatewayPerTenantCredentialsIT extends AbstractGatewayIT {

    private static final String A = "TENANT_GW_PT_A";
    private static final String B_MISMAPPED = "TENANT_GW_PT_B";
    private static final String UNMAPPED = "TENANT_GW_PT_NONE";

    @DynamicPropertySource
    static void perTenantClients(DynamicPropertyRegistry registry) {
        registry.add("reconcile.gateway.tenants[" + A + "].client-id", () -> "recon-pt-a");
        registry.add("reconcile.gateway.tenants[" + A + "].client-secret-env", () -> "GW_PT_SECRET_A");
        registry.add("reconcile.gateway.tenants[" + B_MISMAPPED + "].client-id", () -> "recon-pt-b");
        registry.add("reconcile.gateway.tenants[" + B_MISMAPPED + "].client-secret-env", () -> "GW_PT_SECRET_B");
        // Stand-ins for the environment variables; any Spring property source resolves the named variable.
        registry.add("GW_PT_SECRET_A", () -> "pt-secret-a");
        registry.add("GW_PT_SECRET_B", () -> "pt-secret-b");
    }

    @BeforeEach
    void realmClients() {
        gateway.mapClient("recon-pt-a", A);
        gateway.mapClient("recon-pt-b", A); // registered for the wrong tenant in the realm
    }

    @Test
    void theJobIsSentUnderItsOwnTenantsClient() {
        JsonPath job = investigate(analyst(A), "/fixtures/evidence/timing-only.txt");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        List<Call> calls = gateway.calls();
        assertThat(calls).hasSize(1);
        assertThat(gateway.tenantOfCall(calls.get(0))).isEqualTo(A);
        assertThat(gateway.tokenForms()).anySatisfy(form -> assertThat(form).contains("client_id=recon-pt-a"));
        assertThat(gateway.tokenForms()).noneSatisfy(form -> assertThat(form).contains("pt-secret-b"));
    }

    @Test
    void aTenantWithNoClientFailsBeforeSending() {
        assertRefusedBeforeSending(UNMAPPED, "TENANT_CREDENTIALS_MISSING");
    }

    @Test
    void aClientWhoseTokenClaimsAnotherTenantFailsBeforeSending() {
        assertRefusedBeforeSending(B_MISMAPPED, "TOKEN_TENANT_MISMATCH");
    }

    private void assertRefusedBeforeSending(String tenant, String code) {
        String analyst = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, jobId);

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_" + code);
        assertThat(job.getBoolean("outcomeUnknown")).as("nothing was sent, so nothing is unknown").isFalse();
        assertThat(breakStatus(analyst, breakId)).isEqualTo("FAILED_RETRYABLE");
        assertThat(gateway.calls()).as("no chat request left reconcile-ai").isEmpty();

        List<Map<String, Object>> logged = gatewayCalls(jobId);
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).get("outcome")).isEqualTo("NOT_SENT");
        assertThat(logged.get(0).get("http_status")).isEqualTo(0);
        assertThat(logged.get(0).get("code")).isEqualTo(code);
    }

    @Test
    void theLedgerIsReadWithTheTenantsOwnTokenWhenReconciling() {
        String analyst = analyst(A);
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        UUID invocation = UUID.randomUUID();
        gateway.enqueue(new Scripted(504, "UNKNOWN_OUTCOME", invocation, null));
        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        InvestigationTestSupport.awaitTerminal(analyst, jobId);
        assertThat(gateway.ledger(invocation).tenantId()).as("the gateway scoped the row to the job's tenant").isEqualTo(A);

        given().header("Authorization", JwtTestSupport.bearer("gw-approver-l2", A, List.of("APPROVER_L2")))
            .contentType("application/json").body(Map.of("reference", invocation.toString()))
            .when().post("/api/v1/investigations/" + jobId + "/reconcile")
            .then().statusCode(200).body("ledgerExecutionCertainty", equalTo("UNKNOWN"));
    }
}
