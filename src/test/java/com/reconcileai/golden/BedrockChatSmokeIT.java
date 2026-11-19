// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.golden;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test against REAL Amazon Bedrock. Costs a fraction of a cent. Excluded from the default build by the "bedrock"
 * tag and skipped unless BEDROCK_LIVE=true, so it can never run by accident.
 *
 * <pre>
 *  aws sso login --profile your-profile     (or set AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN)
 *  $env:AWS_REGION = "us-east-1"; $env:BEDROCK_LIVE = "true"
 *  mvn verify "-Dfailsafe.excludedGroups=openai,ollama" "-Dit.test=BedrockChatSmokeIT"
 * </pre>
 * Like the OpenAI smoke test it asserts safety, not model quality: whatever the model says, the job ends in a
 * persisted, policy-routed proposal (or a clean failure), never RESOLVED, and the call is recorded with tokens.
 */
@Tag("bedrock")
@EnabledIfEnvironmentVariable(named = "BEDROCK_LIVE", matches = "true")
@ActiveProfiles(value = {"test", "bedrock"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.ai.model.embedding=none")
class BedrockChatSmokeIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Test
    void realBedrockOutputIsVerifiedRoutedAndRecorded() {
        RestAssured.port = port;
        String tenant = "TENANT_BEDROCK_LIVE";
        String analyst = JwtTestSupport.bearer("bedrock-a", tenant, List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));
        assertThat(job.getString("status")).isIn("SUCCEEDED", "FAILED");
        if ("SUCCEEDED".equals(job.getString("status"))) {
            JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
            assertThat(p.getString("route")).isIn("L1_APPROVAL", "L2_APPROVAL", "MANUAL_REVIEW");
        }
        String status = RestAssured.given().header("Authorization", analyst).get("/api/v1/breaks/" + breakId).path("status");
        assertThat(status).isNotEqualTo("RESOLVED");
        Integer calls = adminJdbc().queryForObject(
            "SELECT COUNT(*) FROM llm_call WHERE tenant_id = ? AND prompt_tokens > 0 AND outcome = 'OK'", Integer.class, tenant);
        assertThat(calls).isGreaterThan(0);
    }
}
