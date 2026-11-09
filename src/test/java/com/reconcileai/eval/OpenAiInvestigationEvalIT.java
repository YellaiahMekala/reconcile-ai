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
 * The same cases against the real OpenAI chat model. Costs a few cents, so it is excluded from the default build and
 * runs only when you ask for it and OPENAI_API_KEY is set:
 *
 * <pre>
 *  $env:OPENAI_API_KEY = "..."
 *  mvn verify -Dfailsafe.excludedGroups=ollama -Dit.test=OpenAiInvestigationEvalIT
 * </pre>
 *
 * Embeddings stay on the test hashing model (same as OpenAiChatSmokeIT), so only chat is real. The test reports and
 * never fails on accuracy; the only assertion is that every case produced a result.
 */
@Tag("openai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles(value = {"test", "openai"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class OpenAiInvestigationEvalIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void measureTheRealModel() {
        String model = System.getenv().getOrDefault("OPENAI_CHAT_MODEL", "gpt-4o-mini");
        EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), "openai-" + model,
            "Chat model: " + model + " (temperature 0). Embeddings: test hashing model, so the dense leg is not semantic.");
        assertThat(outcome.results()).hasSize(outcome.summary().cases());
    }
}
