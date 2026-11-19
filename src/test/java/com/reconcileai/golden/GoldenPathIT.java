// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.golden;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static com.reconcileai.support.ApprovalTestSupport.*;
import static com.reconcileai.support.InvestigationTestSupport.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end gate: the whole product, end to end, with the mock-llm profile.
 *
 * <pre>
 *  analyst: create break -> upload evidence -> investigate (202) -> SSE/poll -> proposal (L1 or L2) + approval task
 *  maker tries to approve own proposal         -> 403 MAKER_CHECKER_VIOLATION (and that attempt IS audited)
 *  L1 checker on an L2 task                    -> 403 INSUFFICIENT_APPROVAL_TIER
 *  correct checker approves                    -> break RESOLVED, proposal APPROVED, evidence frozen
 *  audit chain for the tenant                  -> verifies end to end
 * </pre>
 */
class GoldenPathIT extends AbstractPostgresIT {

    private static final String TENANT = "TENANT_GOLD";

    @LocalServerPort
    int port;

    // The maker ALSO holds APPROVER_L1: maker-checker must hold even for a user with both roles.
    final String maker = JwtTestSupport.bearer("gold-maker", TENANT, List.of("RECON_ANALYST", "APPROVER_L1"));
    final String checkerL1 = JwtTestSupport.bearer("gold-checker-l1", TENANT, List.of("APPROVER_L1"));
    final String checkerL2 = JwtTestSupport.bearer("gold-checker-l2", TENANT, List.of("APPROVER_L2"));
    final String sre = JwtTestSupport.bearer("gold-sre", TENANT, List.of("SRE_ADMIN"));
    final String foreignApprover = JwtTestSupport.bearer("gold-foreign", "TENANT_GOLD_OTHER", List.of("APPROVER_L1", "APPROVER_L2"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private record Investigated(String breakId, String jobId, JsonPath proposal) {}

    private Investigated investigateBreak(String ledger, String external, String fixture) {
        String breakId = ApiTestSupport.createBreak(maker, ledger, external);
        ApiTestSupport.upload(maker, breakId, fixture, "text/plain");
        String jobId = start(maker, breakId, null);
        JsonPath job = awaitTerminal(maker, jobId);
        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        return new Investigated(breakId, jobId, proposal(maker, job.getString("proposalId")));
    }

    @Test
    void l1ProposalIsResolvedOnlyByADifferentChecker() {
        Investigated inv = investigateBreak("50500.00", "50000.00", "/fixtures/evidence/bank-statement-timing.txt");
        String proposalId = inv.proposal().getString("id");
        assertThat(inv.proposal().getString("route")).isEqualTo("L1_APPROVAL");
        assertThat(breakStatus(maker, inv.breakId())).isEqualTo("PENDING_L1");

        // the maker (who also holds APPROVER_L1) never sees their own proposal in the queue
        assertThat(taskIdForProposal(maker, proposalId)).isEmpty();
        String taskId = taskIdForProposal(checkerL1, proposalId).orElseThrow();

        // ...and cannot decide it by id either
        decide(maker, taskId, "APPROVE", "looks right to me").statusCode(403).body("code", equalTo("MAKER_CHECKER_VIOLATION"));
        assertThat(breakStatus(maker, inv.breakId())).isEqualTo("PENDING_L1");

        // another tenant cannot even confirm the task exists
        given().header("Authorization", foreignApprover).when().get("/api/v1/approvals/" + taskId).then().statusCode(404);
        decide(foreignApprover, taskId, "APPROVE", "x").statusCode(404);

        // analysts without an approver role are stopped at the controller
        String pureAnalyst = JwtTestSupport.bearer("gold-analyst-2", TENANT, List.of("RECON_ANALYST"));
        decide(pureAnalyst, taskId, "APPROVE", "x").statusCode(403);

        // the real checker approves
        decide(checkerL1, taskId, "APPROVE", "Fee confirmed on statement line").statusCode(200)
            .body("status", equalTo("APPROVED")).body("decidedBy", equalTo("gold-checker-l1"));

        assertThat(breakStatus(maker, inv.breakId())).isEqualTo("RESOLVED");
        assertThat(proposal(maker, proposalId).getString("status")).isEqualTo("APPROVED");

        // terminal: no second decision, no new evidence, no new investigation
        decide(checkerL2, taskId, "REJECT", "late").statusCode(409).body("code", equalTo("APPROVAL_ALREADY_DECIDED"));
        given().header("Authorization", maker).multiPart("file", "late.txt", "late evidence".getBytes(), "text/plain")
            .when().post("/api/v1/breaks/" + inv.breakId() + "/evidence").then().statusCode(409);
        given().header("Authorization", maker).contentType(ContentType.JSON).body("{}")
            .when().post("/api/v1/breaks/" + inv.breakId() + "/investigations").then().statusCode(409);

        // audit trail tells the whole story, in order, including the blocked self-approval
        assertThat(auditEventTypes(sre, inv.breakId())).containsSubsequence(
            "BREAK_CREATED", "INVESTIGATION_REQUESTED", "INVESTIGATION_STARTED", "PROPOSAL_ROUTED",
            "MAKER_CHECKER_BLOCKED", "RESOLUTION_COMMITTED");

        given().header("Authorization", sre).when().get("/api/v1/audit/verify")
            .then().statusCode(200).body("valid", equalTo(true));
    }

    @Test
    void materialVarianceNeedsAnL2Checker() {
        Investigated inv = investigateBreak("80000.00", "50000.00", "/fixtures/evidence/timing-only.txt");
        String proposalId = inv.proposal().getString("id");
        assertThat(inv.proposal().getString("route")).isEqualTo("L2_APPROVAL");
        assertThat(breakStatus(maker, inv.breakId())).isEqualTo("PENDING_L2");

        assertThat(taskIdForProposal(checkerL1, proposalId)).as("L1 queue must not show L2 work").isEmpty();
        String taskId = taskIdForProposal(checkerL2, proposalId).orElseThrow();

        decide(checkerL1, taskId, "APPROVE", "x").statusCode(403).body("code", equalTo("INSUFFICIENT_APPROVAL_TIER"));
        decide(checkerL2, taskId, "APPROVE", "Timing difference, settles T+1").statusCode(200);
        assertThat(breakStatus(maker, inv.breakId())).isEqualTo("RESOLVED");
    }

    @Test
    void rejectedProposalSendsTheBreakBackForReinvestigation() {
        Investigated inv = investigateBreak("50500.00", "50000.00", "/fixtures/evidence/bank-statement-timing.txt");
        String taskId = taskIdForProposal(checkerL1, inv.proposal().getString("id")).orElseThrow();

        decide(checkerL1, taskId, "REJECT", "Statement is for the wrong account").statusCode(200)
            .body("status", equalTo("REJECTED"));
        assertThat(breakStatus(maker, inv.breakId())).isEqualTo("REJECTED");
        assertThat(proposal(maker, inv.proposal().getString("id")).getString("status")).isEqualTo("REJECTED");

        // analyst can add evidence and try again; a fresh proposal gets a fresh task
        ApiTestSupport.upload(maker, inv.breakId(), "/fixtures/evidence/timing-only.txt", "text/plain");
        JsonPath job2 = awaitTerminal(maker, start(maker, inv.breakId(), null));
        assertThat(job2.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job2.getString("proposalId")).isNotEqualTo(inv.proposal().getString("id"));
        assertThat(breakStatus(maker, inv.breakId())).startsWith("PENDING_");
    }

    @Test
    void invalidDecisionBodiesAreRejected() {
        Investigated inv = investigateBreak("50500.00", "50000.00", "/fixtures/evidence/bank-statement-timing.txt");
        String taskId = taskIdForProposal(checkerL1, inv.proposal().getString("id")).orElseThrow();
        given().header("Authorization", checkerL1).contentType(ContentType.JSON).body("{\"decision\":\"MAYBE\"}")
            .when().post("/api/v1/approvals/" + taskId + "/decision").then().statusCode(400);
        given().header("Authorization", checkerL1).contentType(ContentType.JSON).body("{}")
            .when().post("/api/v1/approvals/" + taskId + "/decision").then().statusCode(400);
        assertThat(breakStatus(maker, inv.breakId())).isEqualTo("PENDING_L1");
    }

    @Test
    void manualReviewProposalsGetNoApprovalTask() {
        Investigated inv = investigateBreak("120.00", "100.00", "/fixtures/evidence/vague-note.txt");
        assertThat(inv.proposal().getString("route")).isEqualTo("MANUAL_REVIEW");
        assertThat(taskIdForProposal(checkerL2, inv.proposal().getString("id"))).isEmpty();
    }
}
