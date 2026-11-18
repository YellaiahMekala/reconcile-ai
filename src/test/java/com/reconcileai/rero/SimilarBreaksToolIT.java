package com.reconcileai.rero;

import com.reconcileai.infrastructure.rero.RunTokens;
import com.reconcileai.support.AbstractReroIT;
import com.reconcileai.support.ReroContracts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform TDD section 9 item 4 (exact similar-break tool response fields). The response of
 * {@code GET /internal/rero/v1/jobs/{jobId}/similar-breaks} carries exactly {@code breakRef}, {@code rootCauseCategory}
 * and {@code resolvedAt} per item (Contract C, {@code internal-similar.v1}); nothing else: no explanation, amounts,
 * proposal id or tenant. Only APPROVED proposals on RESOLVED breaks of the token's tenant appear, never the job's own
 * break. Real Postgres (Testcontainers), rows seeded through the owner connection.
 */
class SimilarBreaksToolIT extends AbstractReroIT {

    @Autowired
    RunTokens runTokens;

    /** A resolved break of {@code tenant} with one proposal of the given status; returns the break reference. */
    private String resolvedBreak(String tenant, String rootCause, String proposalStatus) {
        String breakId = breakWithEvidence(tenant, "/fixtures/evidence/fee-reviewer-challenge.txt");
        jdbc.update("UPDATE break_item SET status = 'RESOLVED', updated_at = now() WHERE id = ?::uuid", breakId);
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO investigation_job (id, tenant_id, break_item_id, status, stage, initiated_by, mode, created_at,
                                           started_at, finished_at)
            VALUES (?, ?, ?::uuid, 'SUCCEEDED', 'CALLING_MODEL', 'rero-analyst', 'SINGLE_CALL', now(), now(), now())""",
            jobId, tenant, breakId);
        jdbc.update("""
            INSERT INTO resolution_proposal (id, tenant_id, break_item_id, job_id, root_cause, explanation,
                recommended_action, confidence, cited_chunk_ids, citations_valid, route, route_reason, status,
                maker_subject, variance_amount, currency_code, created_at)
            VALUES (?, ?, ?::uuid, ?, ?, 'synthetic explanation that must not leak', 'post fee', 0.900, '[]', true,
                    'L1_APPROVAL', 'TEST', ?, 'rero-analyst', 500, 'USD', now())""",
            UUID.randomUUID(), tenant, breakId, jobId, rootCause, proposalStatus);
        return jdbc.queryForObject("SELECT break_reference FROM break_item WHERE id = ?::uuid", String.class, breakId);
    }

    @Test
    void itemsCarryExactlyTheContractFieldsAndOnlyApprovedResolvedBreaksOfTheTokenTenant() {
        String approved = resolvedBreak(TENANT_A, "FEE_OR_CHARGE", "APPROVED");
        String rejected = resolvedBreak(TENANT_A, "TIMING_DIFFERENCE", "REJECTED");
        String otherTenant = resolvedBreak(TENANT_B, "DUPLICATE_ENTRY", "APPROVED");

        UUID job = runningJob(TENANT_A, "RERO", 8);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        String body = given().header("Authorization", "Bearer " + token)
            .when().get("/internal/rero/v1/jobs/" + job + "/similar-breaks")
            .then().statusCode(200).extract().asString();

        assertThat(ReroContracts.validate("internal-similar.v1", body)).isEmpty();
        List<Map<String, Object>> items = io.restassured.path.json.JsonPath.from(body).getList("items");
        assertThat(items).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        for (Map<String, Object> item : items) {
            assertThat(item.keySet()).isEqualTo(Set.of("breakRef", "rootCauseCategory", "resolvedAt"));
        }
        List<Object> refs = items.stream().map(i -> i.get("breakRef")).toList();
        assertThat(refs).contains(approved).doesNotContain(rejected, otherTenant);
        assertThat(items).filteredOn(i -> approved.equals(i.get("breakRef")))
            .singleElement().satisfies(i -> assertThat(i.get("rootCauseCategory")).isEqualTo("FEE_OR_CHARGE"));
        assertThat(body).doesNotContain("synthetic explanation", TENANT_A, TENANT_B);
    }
}
