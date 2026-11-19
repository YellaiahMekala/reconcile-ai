// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.ai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.reconcileai.infrastructure.ai.resilience.LlmResilience;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Amazon Bedrock adapter against a fake Converse endpoint (WireMock, in this JVM): no AWS account, no cost.
 *
 * <p>Proves what a swap from OpenAI to Bedrock must not break: the structured answer is parsed (also when the model
 * wraps it in a markdown fence), tokens and cost are recorded, the output cap really reaches Bedrock, throttling is
 * retried once per our policy (the AWS SDK's own retries are off), a bad request is not retried, and a sustained outage
 * opens the circuit breaker so further jobs fail without calling Bedrock.
 */
@ActiveProfiles(value = {"test", "bedrock-fake"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class BedrockAdapterIT extends AbstractPostgresIT {

    private static final String CONVERSE = "/model/.+/converse";
    private static final WireMockServer BEDROCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        BEDROCK.start();
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
    @Autowired
    LlmResilience resilience;
    @Autowired
    MeterRegistry meters;

    private String analyst;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        BEDROCK.resetAll();
        resilience.reset();
        analyst = JwtTestSupport.bearer("maker-bedrock", "TENANT_BEDROCK", List.of("RECON_ANALYST"));
    }

    @AfterEach
    void tearDown() {
        resilience.reset(); // the breaker is shared by the cached context
    }

    @Test
    void structuredAnswerIsParsedAndTokensCostAndOutputCapAreRecorded() throws IOException {
        stubConverse(200, "converse-ok.json");
        double costBefore = meters.counter("reconcile.llm.cost.usd", "model", "amazon.nova-lite-v1:0").count();

        JsonPath proposal = InvestigationTestSupport.investigate(analyst, "30000.00", "29990.00",
            "/fixtures/evidence/bank-statement-timing.txt");

        assertThat(proposal.getString("rootCause")).isEqualTo("FEE_OR_CHARGE");
        Map<String, Object> call = adminJdbc().queryForMap(
            "SELECT model, prompt_tokens, completion_tokens, outcome FROM llm_call WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1",
            "TENANT_BEDROCK");
        assertThat(call).containsEntry("model", "amazon.nova-lite-v1:0").containsEntry("prompt_tokens", 1200)
            .containsEntry("completion_tokens", 300).containsEntry("outcome", "OK");
        double cost = meters.counter("reconcile.llm.cost.usd", "model", "amazon.nova-lite-v1:0").count() - costBefore;
        assertThat(cost).isCloseTo((1200 * 0.06 + 300 * 0.24) / 1_000_000d, org.assertj.core.data.Offset.offset(1e-9));
        // The 700-token output cap must reach Bedrock as inferenceConfig.maxTokens, not be silently dropped.
        BEDROCK.verify(1, postRequestedFor(urlPathMatching(CONVERSE))
            .withRequestBody(matchingJsonPath("$.inferenceConfig.maxTokens", equalTo("700"))));
    }

    @Test
    void answerWrappedInAMarkdownFenceIsStillParsed() throws IOException {
        stubConverse(200, "converse-fenced.json");

        JsonPath proposal = InvestigationTestSupport.investigate(analyst, "30000.00", "29990.00",
            "/fixtures/evidence/bank-statement-timing.txt");

        assertThat(proposal.getString("rootCause")).isEqualTo("TIMING_DIFFERENCE");
    }

    @Test
    void throttlingIsRetriedOnceByOurPolicyAndThenSucceeds() throws IOException {
        BEDROCK.stubFor(post(urlPathMatching(CONVERSE)).inScenario("throttle").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(response(429, "throttled.json").withHeader("x-amzn-ErrorType", "ThrottlingException"))
            .willSetStateTo("recovered"));
        BEDROCK.stubFor(post(urlPathMatching(CONVERSE)).inScenario("throttle").whenScenarioStateIs("recovered")
            .willReturn(response(200, "converse-ok.json")));

        JsonPath proposal = InvestigationTestSupport.investigate(analyst, "30000.00", "29990.00",
            "/fixtures/evidence/bank-statement-timing.txt");

        assertThat(proposal.getString("rootCause")).isEqualTo("FEE_OR_CHARGE");
        // Exactly 2 requests: the AWS SDK did not add retries of its own on top of LlmResilience.
        BEDROCK.verify(2, postRequestedFor(urlPathMatching(CONVERSE)));
    }

    @Test
    void textThatIsNotJsonFailsTheJobWithoutRetrying() throws IOException {
        stubConverse(200, "converse-not-json.json");

        JsonPath job = failedJob();

        assertThat(job.getString("failureReason")).isEqualTo("MODEL_OUTPUT_NOT_VALID_JSON");
        BEDROCK.verify(1, postRequestedFor(urlPathMatching(CONVERSE)));
    }

    @Test
    void badRequestIsNotRetried() throws IOException {
        BEDROCK.stubFor(post(urlPathMatching(CONVERSE))
            .willReturn(response(400, "validation-error.json").withHeader("x-amzn-ErrorType", "ValidationException")));

        failedJob();

        BEDROCK.verify(1, postRequestedFor(urlPathMatching(CONVERSE)));
    }

    @Test
    void sustainedOutageOpensTheBreakerAndLaterJobsNeverReachBedrock() throws IOException {
        BEDROCK.stubFor(post(urlPathMatching(CONVERSE))
            .willReturn(response(503, "unavailable.json").withHeader("x-amzn-ErrorType", "ServiceUnavailableException")));

        for (int i = 0; i < 8 && resilience.state() != CircuitBreaker.State.OPEN; i++) {
            JsonPath job = failedJob();
            assertThat(job.getString("failureReason")).isEqualTo("LLM_UNAVAILABLE");
        }
        assertThat(resilience.state()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = BEDROCK.countRequestsMatching(postRequestedFor(urlPathMatching(CONVERSE)).build()).getCount();
        JsonPath blocked = failedJob();

        assertThat(blocked.getString("failureReason")).isEqualTo("LLM_CIRCUIT_OPEN");
        assertThat(BEDROCK.countRequestsMatching(postRequestedFor(urlPathMatching(CONVERSE)).build()).getCount())
            .isEqualTo(callsBefore);
    }

    /** A break with evidence whose investigation is expected to end FAILED; returns the job JSON. */
    private JsonPath failedJob() {
        String breakId = ApiTestSupport.createBreak(analyst, "30000.00", "29990.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));
        assertThat(job.getString("status")).isEqualTo("FAILED");
        return job;
    }

    private void stubConverse(int status, String file) throws IOException {
        BEDROCK.stubFor(post(urlPathMatching(CONVERSE)).willReturn(response(status, file)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response(int status, String file)
        throws IOException {
        try (var in = BedrockAdapterIT.class.getResourceAsStream("/wiremock/bedrock/" + file)) {
            return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
