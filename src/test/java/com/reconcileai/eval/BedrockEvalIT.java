package com.reconcileai.eval;

import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same cases against real Amazon Bedrock. Costs a few cents, so it is excluded from the default build (tag
 * "bedrock") and runs only when BEDROCK_LIVE=true and AWS credentials are available:
 *
 * <pre>
 *  $env:AWS_REGION = "us-east-1"; $env:BEDROCK_LIVE = "true"
 *  mvn verify "-Dfailsafe.excludedGroups=openai,ollama" "-Dit.test=BedrockEvalIT"
 * </pre>
 *
 * Embeddings stay on the test hashing model, so only chat is real and the numbers are comparable with the OpenAI
 * run (same cases, same retrieval). The test reports and never fails on accuracy; compare with scripts/eval-compare.ps1.
 */
@Tag("bedrock")
@EnabledIfEnvironmentVariable(named = "BEDROCK_LIVE", matches = "true")
@ActiveProfiles(value = {"test", "bedrock"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class BedrockEvalIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void measureTheRealModel() {
        String model = System.getenv().getOrDefault("BEDROCK_CHAT_MODEL", "amazon.nova-lite-v1:0");
        EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), "bedrock-" + model,
            "Chat model: " + model + " (temperature 0). Embeddings: test hashing model, so the dense leg is not semantic.");
        assertThat(outcome.results()).hasSize(outcome.summary().cases());
    }
}
