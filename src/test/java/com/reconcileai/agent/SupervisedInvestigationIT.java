package com.reconcileai.agent;

import com.reconcileai.infrastructure.security.TenantScope;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
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
import static org.hamcrest.Matchers.equalTo;

/**
 * Supervised mode end to end on the scripted model (no network, no cost): both variants draft, comment and review
 * through the real tools, accounting, citation check, policy gate and audit chain. The fault markers in the fixtures
 * play a reviewer that disagrees, a reviewer that forges a quote, a supervisor that skips the review, and a runaway
 * analyst.
 */
class SupervisedInvestigationIT extends AbstractPostgresIT {

    static final String TENANT = "TENANT_SUPERVISED";
    static final String TIMING = "/fixtures/evidence/bank-statement-timing.txt";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate app; // the application connection: row-level security applies

    final JdbcTemplate admin = adminJdbc();
    final String analyst = JwtTestSupport.bearer("sup-maker", TENANT, List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private JsonPath run(String fixture, String mode, String note) {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        return InvestigationTestSupport.investigateWithMode(analyst, breakId, fixture, mode, note);
    }

    private List<Map<String, Object>> trace(String jobId) {
        return admin.queryForList("SELECT seq, tool, outcome, agent_name FROM agent_tool_call WHERE job_id = ?::uuid ORDER BY seq",
            jobId);
    }

    @Test
    void fixedPipelineRunsTheThreeSpecialistsInOrderAndTheReviewerAgrees() {
        JsonPath job = run(TIMING, "SUPERVISED", null);

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getString("mode")).isEqualTo("SUPERVISED");
        assertThat(job.getString("reviewVerdict")).isEqualTo("AGREE");
        JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(p.getString("rootCause")).isEqualTo("FEE_OR_CHARGE");
        assertThat(p.getBoolean("citationsValid")).isTrue();
        assertThat(p.getString("route")).isEqualTo("L1_APPROVAL");

        List<Map<String, Object>> rows = trace(job.getString("id"));
        assertThat(rows).extracting(r -> r.get("tool") + "@" + r.get("agent_name")).containsExactly(
            "getBreakSummary@evidence-analyst", "searchEvidence@evidence-analyst",
            "findSimilarResolvedBreaks@risk-checker", "review@reviewer");
        assertThat(rows.get(3).get("outcome")).isEqualTo("AGREE");
    }

    @Test
    void plannedVariantDelegatesThroughTheSupervisorAndReachesTheSameRoute() {
        JsonPath job = run(TIMING, "SUPERVISED_PLANNED", null);

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getString("reviewVerdict")).isEqualTo("AGREE");
        JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(p.getString("route")).isEqualTo("L1_APPROVAL");

        List<String> calls = trace(job.getString("id")).stream().map(r -> r.get("tool") + "@" + r.get("agent_name")).toList();
        assertThat(calls).containsSubsequence("delegate:evidence-analyst@supervisor", "delegate:risk-checker@supervisor",
            "delegate:reviewer@supervisor");
        assertThat(calls).contains("searchEvidence@evidence-analyst", "review@reviewer");
    }

    @Test
    void aClaimTheReviewerCannotFindInTheCitedChunkSendsTheBreakToAPerson() {
        // Without the reviewer this draft would be FEE_OR_CHARGE at 0.88 with a valid citation: L1 approval.
        JsonPath job = run("/fixtures/evidence/fee-reviewer-challenge.txt", "SUPERVISED", null);

        assertThat(job.getString("reviewVerdict")).isEqualTo("CHALLENGE");
        JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(p.getString("rootCause")).isEqualTo("FEE_OR_CHARGE");
        assertThat(p.getBoolean("citationsValid")).isTrue();
        assertThat(p.getString("route")).isEqualTo("MANUAL_REVIEW");
        assertThat(p.getString("routeReason")).isEqualTo("REVIEWER_CHALLENGE");
    }

    @Test
    void aReviewerThatSaysSupportedWithAQuoteNotInTheChunkIsOverruledByCode() {
        // Plays a reviewer swayed by text in the evidence: it answers "supported" but cannot quote the chunk.
        JsonPath job = run("/fixtures/evidence/fee-forged-quote.txt", "SUPERVISED", null);

        assertThat(job.getString("reviewVerdict")).isEqualTo("CHALLENGE");
        JsonPath p = InvestigationTestSupport.proposal(analyst, job.getString("proposalId"));
        assertThat(p.getString("route")).isEqualTo("MANUAL_REVIEW");
        assertThat(p.getString("routeReason")).isEqualTo("REVIEWER_CHALLENGE");
    }

    @Test
    void aSupervisorThatSkipsTheReviewCannotSkipItTheCodeRunsIt() {
        JsonPath job = run(TIMING, "SUPERVISED_PLANNED", "simulate supervisor skips review");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getString("reviewVerdict")).isEqualTo("AGREE");
        List<String> calls = trace(job.getString("id")).stream().map(r -> String.valueOf(r.get("tool"))).toList();
        assertThat(calls).doesNotContain("delegate:reviewer").contains("review");
        String details = admin.queryForObject(
            "SELECT details FROM audit_event WHERE event_type = 'AGENT_RUN_COMPLETED' AND aggregate_id = ?::uuid",
            String.class, job.getString("breakItemId"));
        assertThat(details).contains("\"plannerOverrides\":1").contains("\"mode\":\"SUPERVISED_PLANNED\"");
    }

    @Test
    void allSpecialistsShareOneBudgetSoARunawayAnalystStopsTheWholeRun() {
        JsonPath job = run("/fixtures/evidence/agent-loop.txt", "SUPERVISED", null);

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).startsWith("AGENT_");
        List<Map<String, Object>> rows = trace(job.getString("id"));
        // 12 tool calls is the budget for the whole run; a few more get LIMIT_REACHED before the run is stopped.
        assertThat(rows).hasSizeLessThanOrEqualTo(20);
        assertThat(rows).extracting(r -> r.get("agent_name")).containsOnly("evidence-analyst");
        assertThat(rows).extracting(r -> r.get("outcome")).contains("LIMIT_REACHED");
        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + job.getString("breakItemId"))
            .then().statusCode(200).body("status", equalTo("FAILED_RETRYABLE"));
    }

    @Test
    void theRunIsOneChainEventWithAPerAgentDigestAndTheChainStillVerifies() {
        JsonPath job = run(TIMING, "SUPERVISED", null);

        String details = admin.queryForObject(
            "SELECT details FROM audit_event WHERE event_type = 'AGENT_RUN_COMPLETED' AND aggregate_id = ?::uuid",
            String.class, job.getString("breakItemId"));
        assertThat(details).contains("\"mode\":\"SUPERVISED\"").contains("\"reviewerVerdict\":\"AGREE\"")
            .containsPattern("\"digest\\.evidence-analyst\":\"[0-9a-f]{64}\"")
            .containsPattern("\"digest\\.reviewer\":\"[0-9a-f]{64}\"")
            .containsPattern("\"traceDigest\":\"[0-9a-f]{64}\"");

        String sre = JwtTestSupport.bearer("sup-sre", TENANT, List.of("SRE_ADMIN"));
        given().header("Authorization", sre).when().get("/api/v1/audit/verify").then().statusCode(200)
            .body("valid", equalTo(true));
    }

    @Test
    void anotherTenantSeesNeitherTheJobNorItsTraceAndOnlyAnalystsCanStartIt() {
        JsonPath job = run(TIMING, "SUPERVISED", null);
        String jobId = job.getString("id");
        String other = JwtTestSupport.bearer("sup-o", "TENANT_SUPERVISED_OTHER", List.of("RECON_ANALYST"));

        given().header("Authorization", other).when().get("/api/v1/investigations/" + jobId).then().statusCode(404);
        given().header("Authorization", other).when().get("/api/v1/investigations/" + jobId + "/tool-calls").then().statusCode(404);
        given().header("Authorization", analyst).when().get("/api/v1/investigations/" + jobId + "/tool-calls")
            .then().statusCode(200).body("agentName", org.hamcrest.Matchers.hasItems("evidence-analyst", "reviewer"));

        String sql = "SELECT count(*) FROM agent_tool_call WHERE job_id = ?::uuid";
        assertThat(TenantScope.call("TENANT_SUPERVISED_OTHER", () -> app.queryForObject(sql, Integer.class, jobId))).isZero();
        assertThat(TenantScope.call(TENANT, () -> app.queryForObject(sql, Integer.class, jobId))).isEqualTo(4);

        String breakId = ApiTestSupport.createBreak(analyst, "100.00", "90.00");
        ApiTestSupport.upload(analyst, breakId, TIMING, "text/plain");
        given().header("Authorization", JwtTestSupport.bearer("sup-ap", TENANT, List.of("APPROVER_L1")))
            .contentType(ContentType.JSON).body("{\"mode\":\"SUPERVISED\"}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations").then().statusCode(403);
    }

    @Test
    void anUnknownModeIsRejectedWithTheListOfValidModes() {
        String breakId = ApiTestSupport.createBreak(analyst, "100.00", "90.00");
        ApiTestSupport.upload(analyst, breakId, TIMING, "text/plain");
        given().header("Authorization", analyst).contentType(ContentType.JSON).body("{\"mode\":\"SWARM\"}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(400).body("message", org.hamcrest.Matchers.containsString("SUPERVISED_PLANNED"));
    }
}
