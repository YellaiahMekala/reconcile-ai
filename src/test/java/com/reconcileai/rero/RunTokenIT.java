// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.rero;

import com.reconcileai.infrastructure.rero.RunTokens;
import com.reconcileai.support.AbstractReroIT;
import com.reconcileai.support.JwtTestSupport;
import com.reconcileai.support.ReroContracts;
import com.reconcileai.support.RunTokenTestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Platform Q3.2 / TDD section 8: the run capability token cannot escape its job, its tenant or read scope, and user
 * credentials and run tokens never cross between the internal and the user chains.
 */
class RunTokenIT extends AbstractReroIT {

    @Autowired
    RunTokens runTokens;

    private static String path(UUID jobId, String tool) {
        return "/internal/rero/v1/jobs/" + jobId + tool;
    }

    private static io.restassured.response.ValidatableResponse get(String token, UUID jobId, String tool) {
        return given().header("Authorization", "Bearer " + token).when().get(path(jobId, tool)).then();
    }

    @Test
    void aMintedTokenReadsAllFourToolsOfItsOwnRunningJob() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));

        String brk = get(token, job, "/break").statusCode(200)
            .body("breakRef", startsWith("BRK-")).body("variance", equalTo("500")).body("currency", equalTo("USD"))
            .body("sourceA", equalTo("ledger 30500")).body("status", notNullValue()).extract().asString();
        String evidence = get(token, job, "/evidence?query=fee").statusCode(200)
            .body("chunks.chunkId", everyItem(notNullValue())).extract().asString();
        String similar = get(token, job, "/similar-breaks").statusCode(200).body("items", notNullValue())
            .extract().asString();
        String knowledge = get(token, job, "/knowledge?query=fees").statusCode(200)
            .body("outcome", equalTo("UNAVAILABLE")).body("procedures", hasSize(0)) // knowledge is off here
            .extract().asString();
        // Provider side of Contract C section 2: every body matches the platform schema.
        assertThat(ReroContracts.validate("internal-break.v1", brk)).isEmpty();
        assertThat(ReroContracts.validate("internal-evidence.v1", evidence)).isEmpty();
        assertThat(ReroContracts.validate("internal-similar.v1", similar)).isEmpty();
        assertThat(ReroContracts.validate("internal-knowledge.v1", knowledge)).isEmpty();

        assertThat(toolCallsUsed(job)).isEqualTo(4);
        // Audited without arguments: tool names and outcomes only, the empty-argument hash, no query text anywhere.
        List<String> tools = jdbc.queryForList("SELECT tool FROM agent_tool_call WHERE job_id = ? ORDER BY seq",
            String.class, job);
        assertThat(tools).containsExactly("break", "evidence", "similar-breaks", "knowledge");
        assertThat(jdbc.queryForList("SELECT DISTINCT arg_hash FROM agent_tool_call WHERE job_id = ?", String.class, job))
            .containsExactly("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        List<String> audit = jdbc.queryForList("SELECT details FROM audit_event WHERE tenant_id = ? "
            + "AND event_type = 'RERO_TOOL_CALL' AND details LIKE ?", String.class, TENANT_A, "%" + job + "%");
        assertThat(audit).hasSize(4).noneMatch(d -> d.contains("fee"));
    }

    @Test
    void servedEvidenceChunksAreRecordedForTheCitationCheck() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        List<String> ids = get(token, job, "/evidence?query=fee").statusCode(200).extract().jsonPath()
            .getList("chunks.chunkId", String.class);
        assertThat(ids).isNotEmpty();
        assertThat(jdbc.queryForList("SELECT chunk_id FROM rero_served_chunk WHERE job_id = ?", String.class, job))
            .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void wrongAudienceIssuerScopeSubjectOrForeignKeyAreUnauthorized() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        List<String> bad = List.of(
            RunTokenTestKeys.sign(TENANT_A, job, b -> b.audience("reconcile-api"), false),
            RunTokenTestKeys.sign(TENANT_A, job, b -> b.issuer("someone-else"), false),
            RunTokenTestKeys.sign(TENANT_A, job, b -> b.claim("scope", "tools:write"), false),
            RunTokenTestKeys.sign(TENANT_A, job, b -> b.claim("scope", "tools:read tools:write"), false),
            RunTokenTestKeys.sign(TENANT_A, job, b -> b.subject("someone"), false),
            RunTokenTestKeys.sign(TENANT_A, job, b -> b.claim("tenant_id", null), false),
            RunTokenTestKeys.sign(TENANT_A, job, b -> b, true),                       // foreign key
            "not-a-jwt");
        for (String token : bad) {
            get(token, job, "/break").statusCode(401).body("code", equalTo("RUN_TOKEN_INVALID"));
        }
        given().when().get(path(job, "/break")).then().statusCode(401).body("code", equalTo("RUN_TOKEN_INVALID"));
        assertThat(toolCallsUsed(job)).isZero();
    }

    @Test
    void expiredOrOverlongTokensAreUnauthorized() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        Instant past = Instant.now().minusSeconds(600);
        String expired = RunTokenTestKeys.sign(TENANT_A, job,
            b -> b.issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(60))), false);
        String overlong = RunTokenTestKeys.sign(TENANT_A, job,
            b -> b.expirationTime(Date.from(Instant.now().plusSeconds(3600))), false);
        get(expired, job, "/break").statusCode(401).body("code", equalTo("RUN_TOKEN_INVALID"));
        get(overlong, job, "/break").statusCode(401).body("code", equalTo("RUN_TOKEN_INVALID"));
    }

    @Test
    void aTokenForOneJobCannotReadAnother() {
        UUID mine = runningJob(TENANT_A, "RERO", 8);
        UUID other = runningJob(TENANT_A, "RERO", 8);
        String token = runTokens.mint(TENANT_A, mine, Duration.ofSeconds(90));
        get(token, other, "/break").statusCode(403).body("code", equalTo("RUN_TOKEN_JOB_MISMATCH"));
        given().header("Authorization", "Bearer " + token).when().get("/internal/rero/v1/jobs/not-a-uuid/break")
            .then().statusCode(403);
        assertThat(toolCallsUsed(other)).isZero();
    }

    @Test
    void aTokenOfAnotherTenantCannotReadTheJob() {
        UUID jobOfA = runningJob(TENANT_A, "RERO", 8);
        String tokenOfB = runTokens.mint(TENANT_B, jobOfA, Duration.ofSeconds(90)); // right job id, wrong tenant
        get(tokenOfB, jobOfA, "/break").statusCode(403).body("code", equalTo("RUN_TOKEN_JOB_MISMATCH"));
        get(tokenOfB, jobOfA, "/evidence?query=fee").statusCode(403);
        assertThat(toolCallsUsed(jobOfA)).isZero();
    }

    @Test
    void terminalAndNonReroJobsAreRefused() {
        UUID done = runningJob(TENANT_A, "RERO", 8);
        setStatus(done, "SUCCEEDED");
        UUID failed = runningJob(TENANT_A, "RERO", 8);
        setStatus(failed, "FAILED");
        UUID single = runningJob(TENANT_A, "SINGLE_CALL", null);
        for (UUID job : List.of(done, failed, single)) {
            get(runTokens.mint(TENANT_A, job, Duration.ofSeconds(90)), job, "/break")
                .statusCode(409).body("code", equalTo("JOB_NOT_RUNNING"));
        }
    }

    @Test
    void aJobThatEndsMidRunRejectsTheSameTokenAtOnce() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        get(token, job, "/break").statusCode(200);
        setStatus(job, "FAILED"); // server-side invalidation: no deny-list, the job state decides
        get(token, job, "/break").statusCode(409).body("code", equalTo("JOB_NOT_RUNNING"));
    }

    @Test
    void aUserJwtIsRejectedOnTheInternalApi() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        String user = JwtTestSupport.mint("rero-analyst", TENANT_A, List.of("RECON_ANALYST", "SRE_ADMIN"));
        get(user, job, "/break").statusCode(401).body("code", equalTo("RUN_TOKEN_INVALID"));
    }

    @Test
    void aRunTokenIsRejectedOnEveryUserApi() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        for (String userPath : List.of("/api/v1/breaks", "/api/v1/investigations/" + job, "/api/v1/audit",
                "/api/v1/approvals")) {
            int status = given().header("Authorization", "Bearer " + token).when().get(userPath).then().extract()
                .statusCode();
            assertThat(status).as(userPath).isEqualTo(401);
        }
    }

    @Test
    void nonGetIsMethodNotAllowedEvenWithAValidToken() {
        UUID job = runningJob(TENANT_A, "RERO", 8);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        given().header("Authorization", "Bearer " + token).when().post(path(job, "/break")).then().statusCode(405);
        given().header("Authorization", "Bearer " + token).when().put(path(job, "/evidence")).then().statusCode(405);
        given().header("Authorization", "Bearer " + token).when().delete(path(job, "/break")).then().statusCode(405);
        given().when().patch(path(job, "/break")).then().statusCode(405);
        assertThat(toolCallsUsed(job)).isZero();
    }

    @Test
    void theBudgetIsEnforcedWith429() {
        UUID job = runningJob(TENANT_A, "RERO", 2);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        get(token, job, "/break").statusCode(200);
        get(token, job, "/similar-breaks").statusCode(200);
        get(token, job, "/break").statusCode(429).body("code", equalTo("TOOL_BUDGET_EXCEEDED"));
        assertThat(toolCallsUsed(job)).isEqualTo(2);
    }

    @Test
    void everyRefusalUsesTheContractErrorBody() {
        UUID job = runningJob(TENANT_A, "RERO", 1);
        UUID other = runningJob(TENANT_A, "RERO", 1);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        get(token, job, "/break").statusCode(200);
        List<String> bodies = List.of(
            get("garbage", job, "/break").statusCode(401).extract().asString(),
            get(token, other, "/break").statusCode(403).extract().asString(),
            get(token, job, "/break").statusCode(429).extract().asString(),
            given().header("Authorization", "Bearer " + token).when().post(path(job, "/break")).then().statusCode(405)
                .extract().asString());
        setStatus(job, "FAILED");
        bodies = new java.util.ArrayList<>(bodies);
        bodies.add(get(token, job, "/break").statusCode(409).extract().asString());
        for (String body : bodies) {
            assertThat(ReroContracts.validate("error.v1", body)).as(body).isEmpty();
        }
    }

    @Test
    void tokenClaimsFollowTheContract() throws Exception {
        UUID job = UUID.randomUUID();
        com.nimbusds.jwt.SignedJWT jwt = com.nimbusds.jwt.SignedJWT.parse(
            runTokens.mint(TENANT_A, job, Duration.ofSeconds(65)));
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(RunTokenTestKeys.KID);
        var c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo("reconcile-ai");
        assertThat(c.getAudience()).containsExactly("reconcile-internal");
        assertThat(c.getSubject()).isEqualTo("rero");
        assertThat(c.getStringClaim("tenant_id")).isEqualTo(TENANT_A);
        assertThat(c.getStringClaim("job_id")).isEqualTo(job.toString());
        assertThat(c.getStringClaim("scope")).isEqualTo("tools:read");
        assertThat(c.getJWTID()).isNotBlank();
        assertThat(Duration.between(c.getIssueTime().toInstant(), c.getExpirationTime().toInstant()))
            .isEqualTo(Duration.ofSeconds(65));
    }
}
