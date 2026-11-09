package com.reconcileai.agent;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Agent mode end to end on the scripted model: the model calls tools, searches the evidence, drafts a proposal, and the
 * existing citation check and policy gate route it. Single-call stays the default.
 */
class AgentInvestigationIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    final JdbcTemplate jdbc = adminJdbc();
    final String analyst = JwtTestSupport.bearer("agent-maker", "TENANT_AGENT_A", List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void agentRunProducesACitedProposalRoutedToL1() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getString("mode")).isEqualTo("AGENT");
        JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(p.getString("rootCause")).isEqualTo("FEE_OR_CHARGE");
        assertThat(p.getBoolean("citationsValid")).isTrue();
        assertThat(p.getList("citedChunkIds")).isNotEmpty();
        assertThat(p.getString("route")).isEqualTo("L1_APPROVAL");
        assertThat(p.getString("makerSubject")).isEqualTo("agent-maker");
    }

    @Test
    void everyModelTurnIsRecordedInLlmCallWithTokens() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt");

        List<String> models = jdbc.queryForList("SELECT model FROM llm_call WHERE job_id = ?::uuid", String.class, job.getString("id"));
        assertThat(models).hasSize(3).containsOnly("mock-agent"); // summary, search, final answer
        Integer tokens = jdbc.queryForObject("SELECT sum(total_tokens) FROM llm_call WHERE job_id = ?::uuid", Integer.class,
            job.getString("id"));
        assertThat(tokens).isPositive();
    }

    @Test
    void agentFindsTimingEvidenceToo() {
        String breakId = ApiTestSupport.createBreak(analyst, "80000.00", "50000.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/timing-only.txt");

        JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(p.getString("rootCause")).isEqualTo("TIMING_DIFFERENCE");
        assertThat(p.getString("route")).isEqualTo("L2_APPROVAL");
    }

    @Test
    void vagueEvidenceEndsInManualReview() {
        String breakId = ApiTestSupport.createBreak(analyst, "120.00", "100.00");
        JsonPath job = InvestigationTestSupport.investigateWithAgent(analyst, breakId, "/fixtures/evidence/vague-note.txt");

        JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(p.getString("route")).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void defaultModeIsStillSingleCall() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        String jobId = given().header("Authorization", analyst).contentType(ContentType.JSON).body("{}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(202).body("mode", equalTo("SINGLE_CALL")).extract().path("id");
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, jobId);

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList("SELECT model FROM llm_call WHERE job_id = ?::uuid", String.class, jobId))
            .containsOnly("mock-llm");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_tool_call WHERE job_id = ?::uuid", Integer.class, jobId)).isZero();
    }

    @Test
    void unknownModeIsRejectedBeforeAnyWork() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        given().header("Authorization", analyst).contentType(ContentType.JSON).body("{\"mode\":\"AUTOPILOT\"}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(400).body("code", equalTo("INPUT_REJECTED"));
        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().body("status", equalTo("READY_TO_INVESTIGATE"));
    }
}
