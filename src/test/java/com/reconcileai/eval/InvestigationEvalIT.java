// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.support.AbstractPostgresIT;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression gate on the deterministic mock-llm. The mock only knows fee and timing wording, so most seed cases are
 * answered "UNKNOWN" and sent to manual review. The floor in eval/thresholds-mock.json pins that behaviour: if a change
 * to retrieval, the prompt, the citation verifier or the policy gate makes the numbers drop, this test fails. Real
 * model quality is measured by OpenAiInvestigationEvalIT and is never a build gate.
 */
class InvestigationEvalIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void mockRunStaysAtOrAboveTheRecordedFloor() throws IOException {
        EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), "mock-llm",
            "Chat model: deterministic mock (fee and timing keywords only). Embeddings: test hashing model. "
                + "These numbers guard the pipeline; they say nothing about real model quality.");
        EvalSummary s = outcome.summary();
        JsonNode floor = thresholds();

        assertThat(s.cases()).isGreaterThanOrEqualTo(8);
        assertThat(s.jobSuccessRate()).isGreaterThanOrEqualTo(floor.get("minJobSuccessRate").asDouble());
        assertThat(s.rootCauseAccuracy()).isGreaterThanOrEqualTo(floor.get("minRootCauseAccuracy").asDouble());
        assertThat(s.routeAccuracy()).isGreaterThanOrEqualTo(floor.get("minRouteAccuracy").asDouble());
        assertThat(s.citationValidRate()).isGreaterThanOrEqualTo(floor.get("minCitationValidRate").asDouble());
        assertThat(s.citationRecall()).isGreaterThanOrEqualTo(floor.get("minCitationRecall").asDouble());

        // Safety, whatever the model said: nothing reached the ledger without a human decision.
        outcome.results().forEach(r -> assertThat(r.route()).isNotEqualTo("AUTO_RESOLVE"));
    }

    private static JsonNode thresholds() throws IOException {
        try (InputStream in = InvestigationEvalIT.class.getResourceAsStream("/eval/thresholds-mock.json")) {
            return new ObjectMapper().readTree(in);
        }
    }
}
