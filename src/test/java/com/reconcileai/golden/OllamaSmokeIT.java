package com.reconcileai.golden;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual smoke test against a REAL local model. Excluded from `mvn verify` by the "ollama" tag.
 *
 * <pre>
 *  ollama pull llama3.1:8b
 *  mvn verify -Dfailsafe.excludedGroups=openai -Dit.test=OllamaSmokeIT
 * </pre>
 * Embeddings stay on the deterministic FakeEmbeddingModel so this needs no OpenAI key; only chat is real.
 * Assertions are about SAFETY, not model quality: whatever a real model says, the pipeline must end in a
 * persisted, policy-routed proposal and never in RESOLVED.
 */
@Tag("ollama")
@ActiveProfiles(value = {"test", "ollama"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class OllamaSmokeIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Test
    void realModelOutputIsVerifiedAndRoutedNeverResolved() {
        RestAssured.port = port;
        String analyst = JwtTestSupport.bearer("ollama-a", "TENANT_OLLAMA", List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));
        // A slow or malformed local model may legitimately FAIL (retryable). It must never hang or resolve.
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
    }
}
