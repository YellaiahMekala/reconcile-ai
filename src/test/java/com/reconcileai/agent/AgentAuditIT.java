// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.agent;

import com.reconcileai.infrastructure.security.TenantScope;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** What an agent run leaves behind: tool-call rows, one audit-chain event, and the limits that stop a runaway model. */
class AgentAuditIT extends AbstractPostgresIT {

    static final String TENANT = "TENANT_AGENT_AUDIT";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate app; // the application connection: row-level security applies

    final JdbcTemplate admin = adminJdbc();
    final String analyst = JwtTestSupport.bearer("audit-maker", TENANT, List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void everyToolCallIsStoredWithAHashedArgumentNotItsText() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt");

        List<Map<String, Object>> rows = admin.queryForList(
            "SELECT seq, tool, arg_hash, result_chars, outcome FROM agent_tool_call WHERE job_id = ?::uuid ORDER BY seq",
            job.getString("id"));
        assertThat(rows).extracting(r -> r.get("tool")).containsExactly("getBreakSummary", "searchEvidence");
        assertThat(rows).extracting(r -> r.get("outcome")).containsOnly("OK");
        assertThat(rows).extracting(r -> r.get("arg_hash").toString().trim()).allMatch(h -> h.length() == 64);
        assertThat(rows).extracting(r -> ((Number) r.get("result_chars")).intValue()).allMatch(n -> n > 0);
    }

    @Test
    void oneChainEventCoversTheRunAndCarriesADigestOfTheTrace() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt");

        List<String> details = admin.queryForList(
            "SELECT details FROM audit_event WHERE event_type = 'AGENT_RUN_COMPLETED' AND aggregate_id = ?::uuid",
            String.class, breakId);
        assertThat(details).hasSize(1);
        assertThat(details.get(0)).contains("\"outcome\":\"COMPLETED\"").contains("\"toolCalls\":2")
            .contains("\"modelTurns\":3").containsPattern("\"traceDigest\":\"[0-9a-f]{64}\"");
    }

    @Test
    void theToolCallTimelineIsReadableByTheTenantAndInvisibleToOthers() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt");
        String url = "/api/v1/investigations/" + job.getString("id") + "/tool-calls";

        given().header("Authorization", analyst).when().get(url)
            .then().statusCode(200)
            .body("tool", org.hamcrest.Matchers.contains("getBreakSummary", "searchEvidence"))
            .body("outcome", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("OK")))
            .body("[0].argHash", org.hamcrest.Matchers.nullValue());
        given().header("Authorization", JwtTestSupport.bearer("o1", "TENANT_AGENT_OTHER", List.of("RECON_ANALYST")))
            .when().get(url).then().statusCode(404);
        given().when().get(url).then().statusCode(401);
    }

    @Test
    void toolCallRowsAreIsolatedByTenant() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt");
        String sql = "SELECT count(*) FROM agent_tool_call WHERE job_id = ?::uuid";

        assertThat(app.queryForObject(sql, Integer.class, job.getString("id"))).as("no tenant scope").isZero();
        assertThat(TenantScope.call(TENANT, () -> app.queryForObject(sql, Integer.class, job.getString("id")))).isEqualTo(2);
        assertThat(TenantScope.call("TENANT_AGENT_OTHER", () -> app.queryForObject(sql, Integer.class, job.getString("id")))).isZero();
    }

    @Test
    void aModelThatNeverStopsCallingToolsIsCutOffAndTheJobFailsRetryably() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/agent-loop.txt");

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("AGENT_TURN_LIMIT");
        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("FAILED_RETRYABLE"));

        List<String> outcomes = admin.queryForList(
            "SELECT outcome FROM agent_tool_call WHERE job_id = ?::uuid ORDER BY seq", String.class, job.getString("id"));
        assertThat(outcomes).hasSize(8).startsWith("OK", "OK").endsWith("LIMIT_REACHED", "LIMIT_REACHED");
        String chain = admin.queryForObject(
            "SELECT details FROM audit_event WHERE event_type = 'AGENT_RUN_COMPLETED' AND aggregate_id = ?::uuid",
            String.class, breakId);
        assertThat(chain).contains("\"outcome\":\"FAILED\"");
    }
}
