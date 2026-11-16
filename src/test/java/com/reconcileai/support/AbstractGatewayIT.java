package com.reconcileai.support;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * Base for the gateway-route integration tests: the real application, the real Postgres, and the investigation
 * model call routed through {@link FakeGatewayServer} ({@code reconcile.gateway.enabled=true}). Everything else
 * (retrieval, prompt, citation check, policy gate, audit chain) is the production code path.
 */
public abstract class AbstractGatewayIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    protected final JdbcTemplate jdbc = adminJdbc();
    protected final FakeGatewayServer gateway = FakeGatewayServer.get();

    @DynamicPropertySource
    static void gatewayProps(DynamicPropertyRegistry registry) {
        FakeGatewayServer.register(registry);
    }

    @BeforeEach
    void resetGateway() {
        RestAssured.port = port;
        gateway.reset();
    }

    protected static String analyst(String tenant) {
        return JwtTestSupport.bearer("gw-analyst", tenant, List.of("RECON_ANALYST"));
    }

    protected static String admin(String tenant) {
        return JwtTestSupport.bearer("gw-admin", tenant, List.of("SRE_ADMIN"));
    }

    /** The key the adapter must use for a job's single investigate step (CONSUMER_CONTRACT.md §2). */
    protected static String keyFor(String jobId) {
        return "recon:" + jobId + ":investigate:1";
    }

    /** Create a break, upload one fixture, run the investigation to a terminal state. Returns the job JSON. */
    protected JsonPath investigate(String bearer, String fixture) {
        String breakId = ApiTestSupport.createBreak(bearer, "30500.00", "30000.00");
        ApiTestSupport.upload(bearer, breakId, fixture, "text/plain");
        return InvestigationTestSupport.awaitTerminal(bearer, InvestigationTestSupport.start(bearer, breakId, null));
    }

    protected String breakStatus(String bearer, String breakId) {
        return given().header("Authorization", bearer).get("/api/v1/breaks/" + breakId).then().statusCode(200)
            .extract().path("status");
    }

    protected List<Map<String, Object>> gatewayCalls(String jobId) {
        return jdbc.queryForList(
            "SELECT attempt, outcome, http_status, code, invocation_id, gateway_status, input_tokens, output_tokens "
            + "FROM gateway_call WHERE job_id = ?::uuid ORDER BY attempt", jobId);
    }

    protected Map<String, Object> jobRow(String jobId) {
        return jdbc.queryForMap(
            "SELECT status, failure_reason, outcome_unknown, proposal_id FROM investigation_job WHERE id = ?::uuid", jobId);
    }

    protected String failureAudit(String tenant, String breakId) {
        List<String> rows = jdbc.queryForList(
            "SELECT details FROM audit_event WHERE tenant_id = ? AND aggregate_id = ?::uuid "
            + "AND event_type = 'INVESTIGATION_FAILED' ORDER BY sequence DESC", String.class, tenant, breakId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    protected static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }
}
