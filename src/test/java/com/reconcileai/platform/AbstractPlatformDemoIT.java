package com.reconcileai.platform;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.FakeEkaServer;
import com.reconcileai.support.FakeGatewayServer;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.List;

import static com.reconcileai.support.ApprovalTestSupport.breakStatus;
import static com.reconcileai.support.ApprovalTestSupport.decide;
import static com.reconcileai.support.ApprovalTestSupport.taskIdForProposal;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Key-free, paid-call-free version of the two platform demos (platform Q4.2 and Q4.5), runnable in CI: the real
 * application and Postgres, the {@code mock-llm} profile (no direct chat model), the investigation model call routed
 * through the schema-checked {@link FakeGatewayServer}, and knowledge through {@link FakeEkaServer} or an absent EKA.
 * Per-tenant OAuth clients are configured for both routes, as in the live golden path.
 */
abstract class AbstractPlatformDemoIT extends AbstractPostgresIT {

    static final String TENANT = "TENANT_PLATFORM_DEMO";

    @LocalServerPort
    int port;

    final FakeGatewayServer gateway = FakeGatewayServer.get();
    final FakeEkaServer eka = FakeEkaServer.get();

    final String maker = JwtTestSupport.bearer("demo-maker", TENANT, List.of("RECON_ANALYST"));
    final String checker = JwtTestSupport.bearer("demo-checker", TENANT, List.of("APPROVER_L1"));
    final String sre = JwtTestSupport.bearer("demo-sre", TENANT, List.of("SRE_ADMIN"));

    /** Gateway on with a tenant client; knowledge on, pointed at {@code ekaBaseUrl} with a tenant client. */
    static void register(DynamicPropertyRegistry r, String ekaBaseUrl) {
        FakeGatewayServer.register(r);
        r.add("reconcile.gateway.tenants.[" + TENANT + "].client-id", () -> "recon-demo");
        r.add("reconcile.gateway.tenants.[" + TENANT + "].client-secret-env", () -> "DEMO_GATEWAY_SECRET");
        r.add("DEMO_GATEWAY_SECRET", () -> "synthetic-gateway-secret");
        r.add("reconcile.knowledge.enabled", () -> "true");
        r.add("reconcile.knowledge.base-url", () -> ekaBaseUrl);
        r.add("reconcile.knowledge.token-uri", () -> ekaBaseUrl + "/token");
        r.add("reconcile.knowledge.request-timeout", () -> "1s");
        r.add("reconcile.knowledge.fail-closed", () -> "false");
        r.add("reconcile.knowledge.tenants.[" + TENANT + "].client-id", () -> "svc-demo");
        r.add("reconcile.knowledge.tenants.[" + TENANT + "].client-secret-env", () -> "DEMO_KNOWLEDGE_SECRET");
        r.add("DEMO_KNOWLEDGE_SECRET", () -> "synthetic-knowledge-secret");
    }

    @BeforeEach
    void resetFakes() {
        RestAssured.port = port;
        gateway.reset();
        gateway.mapClient("recon-demo", TENANT);
        eka.reset();
        eka.mapClient("svc-demo", TENANT);
    }

    /** Break, evidence, investigation, a different person's approval, then the audit chain check. */
    JsonPath runDemoToResolution() {
        String breakId = ApiTestSupport.createBreak(maker, "50500.00", "50000.00");
        ApiTestSupport.upload(maker, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        JsonPath job = InvestigationTestSupport.awaitTerminal(maker, InvestigationTestSupport.start(maker, breakId, null));
        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");

        String proposalId = job.getString("proposalId");
        assertThat(InvestigationTestSupport.proposal(maker, proposalId).getString("route")).isEqualTo("L1_APPROVAL");
        assertThat(gateway.calls()).as("the only chat path is the gateway").hasSize(1);
        assertThat(gateway.tenantOfCall(gateway.calls().get(0))).isEqualTo(TENANT);

        String taskId = taskIdForProposal(checker, proposalId).orElseThrow();
        decide(checker, taskId, "APPROVE", "Confirmed on the statement").statusCode(200).body("status", equalTo("APPROVED"));
        assertThat(breakStatus(maker, breakId)).isEqualTo("RESOLVED");
        given().header("Authorization", sre).when().get("/api/v1/audit/verify").then().statusCode(200).body("valid", equalTo(true));
        return job;
    }
}
