// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real comparison for Phase 4G: all four modes on the 28 easy and 12 hard cases against the OpenAI chat model,
 * same model and temperature 0 for every agent. Costs money (estimated well under 1 USD for gpt-4o-mini), so it is
 * excluded from the default build and runs only on request:
 *
 * <pre>
 *  $env:OPENAI_API_KEY = "..."
 *  mvn verify -Dfailsafe.excludedGroups=ollama -Dit.test=OpenAiModeComparisonIT
 * </pre>
 *
 * Reports only (target/eval/openai-*-mode-comparison.md); the decision rule is applied in the report, not asserted,
 * because a negative result is a valid result.
 */
@Tag("openai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles(value = {"test", "openai"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class OpenAiModeComparisonIT extends AbstractPostgresIT {

    @org.springframework.beans.factory.annotation.Autowired
    com.reconcileai.infrastructure.ai.resilience.LlmResilience llmResilience;

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void compareAllModesOnTheRealModel() {
        String model = System.getenv().getOrDefault("OPENAI_CHAT_MODEL", "gpt-4o-mini");
        String label = "openai-" + model;
        String notes = "Chat model: " + model + " (temperature 0), same model for every agent. Embeddings: test hashing model. "
            + "Cases: 28 easy + 12 hard, labelled before the run.";
        List<ModeComparison.Row> rows = ModeComparison.run(adminJdbc(), label, notes, ModeComparison.ALL_MODES, true, llmResilience::reset);
        ModeComparison.write(label, notes, rows);
        assertThat(rows).hasSize(ModeComparison.ALL_MODES.size() * 2);
        // A job that failed because the provider was unreachable (LLM_UNAVAILABLE, LLM_CIRCUIT_OPEN) measures the
        // network, not the mode: fail loudly instead of reporting it. A job that ran out of its budget
        // (AGENT_TURN_LIMIT and friends) is a real result and stays in the report.
        assertThat(rows).allSatisfy(r -> assertThat(r.results())
            .as("provider failures in " + r.mode() + " " + r.set())
            .noneMatch(c -> c.failureReason() != null && c.failureReason().startsWith("LLM_")));
    }
}
