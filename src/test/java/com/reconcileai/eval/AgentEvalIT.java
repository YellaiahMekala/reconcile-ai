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
 * The same 28 cases in AGENT mode on the scripted model. The scripted model answers with the same keyword rules as the
 * mock single-call model, so this gate guards the agent plumbing (tools, retrieval allow-list, citation check, gate),
 * not model quality. The real comparison is OpenAiAgentEvalIT against OpenAiInvestigationEvalIT.
 */
class AgentEvalIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void agentRunStaysAtOrAboveTheRecordedFloor() throws IOException {
        EvalHarness.Outcome outcome = EvalHarness.runAndReport(adminJdbc(), "mock-agent",
            "Mode: AGENT. Chat model: scripted tool-calling stand-in (summary, search, answer). Embeddings: test hashing model. "
                + "These numbers guard the agent pipeline; they say nothing about real model quality.", true);
        EvalSummary s = outcome.summary();
        JsonNode floor;
        try (InputStream in = AgentEvalIT.class.getResourceAsStream("/eval/thresholds-mock.json")) {
            floor = new ObjectMapper().readTree(in);
        }

        assertThat(s.jobSuccessRate()).isGreaterThanOrEqualTo(floor.get("minJobSuccessRate").asDouble());
        assertThat(s.rootCauseAccuracy()).isGreaterThanOrEqualTo(floor.get("minRootCauseAccuracy").asDouble());
        assertThat(s.routeAccuracy()).isGreaterThanOrEqualTo(floor.get("minRouteAccuracy").asDouble());
        assertThat(s.citationValidRate()).isGreaterThanOrEqualTo(floor.get("minCitationValidRate").asDouble());
        assertThat(s.citationRecall()).isGreaterThanOrEqualTo(floor.get("minCitationRecall").asDouble());
        outcome.results().forEach(r -> assertThat(r.route()).isNotEqualTo("AUTO_RESOLVE"));
    }
}
