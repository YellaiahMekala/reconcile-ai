// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.reconcileai.infrastructure.ai.llm.MockLlmChatModel;
import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4D.4 run locally: the full eval set through the real Bedrock adapter (AWS SDK, Converse wire format, usage and
 * cost recording) with no AWS account. The fake Converse endpoint answers with the same deterministic rules as
 * mock-llm, so every score must reach the mock-llm floor: any drop is a defect in the adapter (lost prompt text,
 * broken parsing, dropped citations), not in a model. Real Bedrock quality is measured by BedrockEvalIT.
 */
@ActiveProfiles(value = {"test", "bedrock-fake"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class BedrockFakeEvalIT extends AbstractPostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer BEDROCK =
        new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new MockRulesConverse()));

    static {
        BEDROCK.start();
        BEDROCK.stubFor(post(urlPathMatching("/model/.+/converse"))
            .willReturn(aResponse().withTransformers(MockRulesConverse.NAME)));
    }

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("reconcile.bedrock.endpoint-override", () -> "http://localhost:" + BEDROCK.port());
    }

    @AfterAll
    static void stopFake() {
        BEDROCK.stop();
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void bedrockAdapterScoresTheSameAsTheDirectMock() throws IOException {
        // Other suites in the same database also record Bedrock-labelled calls; count only this run's rows.
        java.time.OffsetDateTime started = adminJdbc().queryForObject("SELECT now()", java.time.OffsetDateTime.class);
        EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), "bedrock-fake",
            "Chat: fake Bedrock Converse endpoint answering with the mock-llm rules, through the real Bedrock adapter. "
                + "Measures adapter fidelity, not model quality.");
        EvalSummary s = outcome.summary();
        JsonNode floor;
        try (InputStream in = getClass().getResourceAsStream("/eval/thresholds-mock.json")) {
            floor = JSON.readTree(in);
        }

        assertThat(s.cases()).isGreaterThanOrEqualTo(8);
        assertThat(s.jobSuccessRate()).isGreaterThanOrEqualTo(floor.get("minJobSuccessRate").asDouble());
        assertThat(s.rootCauseAccuracy()).isGreaterThanOrEqualTo(floor.get("minRootCauseAccuracy").asDouble());
        assertThat(s.routeAccuracy()).isGreaterThanOrEqualTo(floor.get("minRouteAccuracy").asDouble());
        assertThat(s.citationValidRate()).isGreaterThanOrEqualTo(floor.get("minCitationValidRate").asDouble());
        assertThat(s.citationRecall()).isGreaterThanOrEqualTo(floor.get("minCitationRecall").asDouble());
        outcome.results().forEach(r -> assertThat(r.route()).isNotEqualTo("AUTO_RESOLVE"));

        // Every case that reached a model went through Bedrock (a few are answered from the semantic cache and make no call).
        long modelCalls = outcome.results().stream().filter(r -> r.promptTokens() > 0).count();
        assertThat(modelCalls).isGreaterThan(0);
        outcome.results().stream().filter(r -> r.promptTokens() > 0)
            .forEach(r -> assertThat(r.model()).as("model label for " + r.caseId()).isEqualTo("amazon.nova-lite-v1:0"));
        Integer bedrockCalls = adminJdbc().queryForObject(
            "SELECT COUNT(*) FROM llm_call WHERE model = 'amazon.nova-lite-v1:0' AND prompt_tokens > 0 AND outcome = 'OK' AND created_at >= ?",
            Integer.class, started);
        assertThat(bedrockCalls).isEqualTo((int) modelCalls);
    }

    /** Reads the Converse request, applies the mock-llm rules to its text and answers in Converse format. */
    static final class MockRulesConverse implements ResponseDefinitionTransformerV2 {
        static final String NAME = "mock-rules-converse";

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            try {
                JsonNode request = JSON.readTree(event.getRequest().getBodyAsString());
                StringBuilder text = new StringBuilder();
                request.path("system").forEach(b -> text.append(b.path("text").asText()).append('\n'));
                request.path("messages").forEach(m -> m.path("content").forEach(c -> text.append(c.path("text").asText()).append('\n')));
                String answer = MockLlmChatModel.answer(MockLlmChatModel.evidenceBlocks(text.toString()));

                ObjectNode body = JSON.createObjectNode();
                ObjectNode message = body.putObject("output").putObject("message");
                message.put("role", "assistant");
                message.putArray("content").addObject().put("text", answer);
                body.put("stopReason", "end_turn");
                body.putObject("usage")
                    .put("inputTokens", Math.max(1, text.length() / 4))
                    .put("outputTokens", Math.max(1, answer.length() / 4))
                    .put("totalTokens", Math.max(1, text.length() / 4) + Math.max(1, answer.length() / 4));
                body.putObject("metrics").put("latencyMs", 5);
                return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200).withHeader("Content-Type", "application/json").withBody(JSON.writeValueAsString(body)).build();
            } catch (IOException e) {
                return ResponseDefinitionBuilder.responseDefinition().withStatus(400).withBody(e.getMessage()).build();
            }
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public String getName() {
            return NAME;
        }
    }
}
