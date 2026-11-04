package com.reconcileai.golden;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test against the REAL OpenAI chat model. Excluded from the default build by the "openai" tag.
 *
 * <pre>
 *  set OPENAI_API_KEY=...            (PowerShell: $env:OPENAI_API_KEY = "...")
 *  mvn verify -Dfailsafe.excludedGroups=ollama -Dit.test=OpenAiChatSmokeIT
 * </pre>
 * Runs only when OPENAI_API_KEY is set. Embeddings stay on the deterministic FakeEmbeddingModel, so only
 * chat is real. Assertions are about SAFETY, not model quality: whatever the model says, the pipeline ends in
 * a persisted, policy-routed proposal and never in RESOLVED, and the call is recorded in llm_call.
 */
@Tag("openai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles(value = {"test", "openai"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class OpenAiChatSmokeIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void realModelOutputIsVerifiedRoutedAndRecorded() {
        RestAssured.port = port;
        String tenant = "TENANT_OPENAI";
        String analyst = JwtTestSupport.bearer("openai-a", tenant, List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));
        // A real model may legitimately FAIL (for example on malformed JSON). It must never hang or resolve.
        assertThat(job.getString("status")).isIn("SUCCEEDED", "FAILED");
        if ("SUCCEEDED".equals(job.getString("status"))) {
            JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
            assertThat(p.getString("route")).isIn("L1_APPROVAL", "L2_APPROVAL", "MANUAL_REVIEW");
            if (!p.getBoolean("citationsValid")) {
                assertThat(p.getString("route")).isEqualTo("MANUAL_REVIEW");
            }
        }
        String status = RestAssured.given().header("Authorization", analyst).get("/api/v1/breaks/" + breakId).path("status");
        assertThat(status).isNotEqualTo("RESOLVED");

        Integer calls = jdbc.queryForObject(
            "SELECT COUNT(*) FROM llm_call WHERE tenant_id = ? AND prompt_tokens > 0 AND outcome = 'OK'",
            Integer.class, tenant);
        assertThat(calls).isGreaterThan(0);
    }
}
