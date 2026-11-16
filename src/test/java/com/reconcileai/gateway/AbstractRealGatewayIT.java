package com.reconcileai.gateway;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared body of the two opt-in runs against a REAL ai-gateway (never part of {@code mvn verify}):
 * <ul>
 *   <li>{@link RealGatewayIT} ({@code @Tag("gateway")}): the platform spike stack, stub provider, no cost;</li>
 *   <li>{@link GatewayLiveProviderIT} ({@code @Tag("gateway-live")}): a gateway configured with a billable
 *       provider (platform Q2.7). One run, approved in advance, evidence written to a file.</li>
 * </ul>
 * Configuration comes only from the environment (never from a file in the repo):
 * <pre>
 *  GATEWAY_CLIENT_SECRET   required; the test is skipped without it
 *  GATEWAY_BASE_URL        default http://localhost:8280
 *  GATEWAY_TOKEN_URI       default http://localhost:8190/realms/platform/protocol/openid-connect/token
 *  GATEWAY_CLIENT_ID       default svc-reconcile-tenant-a
 *  GATEWAY_TOKEN_SCOPE     default aud-ai-gateway
 * </pre>
 * The assertions are about the contract, not model quality: the job ends, our {@code gateway_call} rows exist,
 * and every invocation id we stored resolves on the gateway ledger ({@code GET /api/v1/invocations/{id}}) under
 * our own service token. A stub provider will usually make the job FAIL on output parsing; that is expected and
 * still proves the link.
 */
abstract class AbstractRealGatewayIT extends AbstractPostgresIT {

    static final String BASE_URL = env("GATEWAY_BASE_URL", "http://localhost:8280");
    static final String TOKEN_URI = env("GATEWAY_TOKEN_URI",
        "http://localhost:8190/realms/platform/protocol/openid-connect/token");
    static final String CLIENT_ID = env("GATEWAY_CLIENT_ID", "svc-reconcile-tenant-a");
    static final String SCOPE = env("GATEWAY_TOKEN_SCOPE", "aud-ai-gateway");

    @LocalServerPort
    int port;

    final JdbcTemplate jdbc = adminJdbc();

    @DynamicPropertySource
    static void realGateway(DynamicPropertyRegistry registry) {
        registry.add("reconcile.gateway.enabled", () -> "true");
        registry.add("reconcile.gateway.base-url", () -> BASE_URL);
        registry.add("reconcile.gateway.token-uri", () -> TOKEN_URI);
        registry.add("reconcile.gateway.client-id", () -> CLIENT_ID);
        registry.add("reconcile.gateway.client-secret", () -> env("GATEWAY_CLIENT_SECRET", ""));
        registry.add("reconcile.gateway.token-scope", () -> SCOPE);
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** One investigation through the real gateway; returns the evidence block (markdown) for the proof doc. */
    String runOnce(String tenant, String fixture) {
        RestAssured.port = port;
        String analyst = JwtTestSupport.bearer("gw-real", tenant, List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, fixture, "text/plain");
        Instant started = Instant.now();
        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, jobId, Duration.ofSeconds(180));

        assertThat(job.getString("status")).isIn("SUCCEEDED", "FAILED");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT attempt, idempotency_key, outcome, http_status, code, invocation_id, gateway_status, "
            + "input_tokens, output_tokens, latency_ms FROM gateway_call WHERE job_id = ?::uuid ORDER BY attempt", jobId);
        assertThat(rows).as("at least one attempt was logged").isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(rows).extracting(r -> r.get("idempotency_key")).containsOnly("recon:" + jobId + ":investigate:1");

        String serviceToken = serviceToken();
        StringBuilder md = new StringBuilder();
        md.append("| field | value |\n|---|---|\n")
            .append("| gateway | ").append(BASE_URL).append(" |\n")
            .append("| tenant | ").append(tenant).append(" |\n")
            .append("| jobId | ").append(jobId).append(" |\n")
            .append("| job status / reason | ").append(job.getString("status")).append(" / ")
            .append(job.getString("failureReason")).append(" |\n")
            .append("| started (UTC) | ").append(started).append(" |\n")
            .append("| finished (UTC) | ").append(Instant.now()).append(" |\n\n")
            .append("| attempt | key | outcome | http | code | invocationId | gateway status | in | out | ms | ledger |\n")
            .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : rows) {
            String ledger = "no invocation id";
            if (r.get("invocation_id") != null) {
                Response view = given().header("Authorization", "Bearer " + serviceToken)
                    .when().get(BASE_URL + "/api/v1/invocations/" + r.get("invocation_id"));
                assertThat(view.statusCode()).as("ledger row for " + r.get("invocation_id")).isEqualTo(200);
                assertThat(view.jsonPath().getString("invocationId")).isEqualTo(r.get("invocation_id").toString());
                ledger = "200 " + view.jsonPath().getString("status") + " " + view.jsonPath().getString("executionCertainty")
                    + " cost=" + view.jsonPath().getString("actualCostUsd");
            }
            md.append("| ").append(r.get("attempt")).append(" | ").append(r.get("idempotency_key")).append(" | ")
                .append(r.get("outcome")).append(" | ").append(r.get("http_status")).append(" | ").append(r.get("code"))
                .append(" | ").append(r.get("invocation_id")).append(" | ").append(r.get("gateway_status")).append(" | ")
                .append(r.get("input_tokens")).append(" | ").append(r.get("output_tokens")).append(" | ")
                .append(r.get("latency_ms")).append(" | ").append(ledger).append(" |\n");
        }
        List<Map<String, Object>> llm = jdbc.queryForList(
            "SELECT model, prompt_tokens, completion_tokens, outcome FROM llm_call WHERE job_id = ?::uuid", jobId);
        md.append("\nllm_call rows: ").append(llm).append('\n');
        System.out.println("\n=== gateway run evidence ===\n" + md + "============================\n");
        return md.toString();
    }

    /** Client-credentials token for the ledger read; the secret is only ever read from the environment. */
    String serviceToken() {
        String form = "grant_type=client_credentials&client_id=" + CLIENT_ID
            + "&client_secret=" + env("GATEWAY_CLIENT_SECRET", "") + "&scope=" + SCOPE;
        Response r = given().contentType("application/x-www-form-urlencoded").body(form).when().post(TOKEN_URI);
        assertThat(r.statusCode()).as("token endpoint").isEqualTo(200);
        return r.jsonPath().getString("access_token");
    }

    static void writeEvidence(Path target, String title, String body) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, "# " + title + "\n\nGenerated " + Instant.now() + " by the test; copy into docs/proof.\n\n"
                + body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
