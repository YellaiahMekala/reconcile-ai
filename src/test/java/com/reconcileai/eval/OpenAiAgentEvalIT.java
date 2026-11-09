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
 * The cases in AGENT mode against the real OpenAI chat model, for the single-call versus agent comparison.
 * Costs more than the single-call run (several model turns per case), so it is excluded from the default build:
 *
 * <pre>
 *  $env:OPENAI_API_KEY = "..."
 *  mvn verify -Dfailsafe.excludedGroups=ollama -Dit.test=OpenAiAgentEvalIT
 * </pre>
 *
 * Reports only; the one assertion is that every case produced a result.
 */
@Tag("openai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles(value = {"test", "openai"}, inheritProfiles = false)
@TestPropertySource(properties = {"spring.ai.model.embedding=none",
    "logging.level.com.reconcileai.infrastructure.ai.agent.ReconTools=DEBUG"})
class OpenAiAgentEvalIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void measureTheRealModelAsAnAgent() {
        String model = System.getenv().getOrDefault("OPENAI_CHAT_MODEL", "gpt-4o-mini");
        EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), "openai-agent-" + model,
            "Mode: AGENT. Chat model: " + model + " (temperature 0). Embeddings: test hashing model.", true);
        assertThat(outcome.results()).hasSize(outcome.summary().cases());
    }
}
