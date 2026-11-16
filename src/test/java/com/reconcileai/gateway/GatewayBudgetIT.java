package com.reconcileai.gateway;

import com.reconcileai.support.AbstractGatewayIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.FakeGatewayServer.Call;
import com.reconcileai.support.FakeGatewayServer.Scripted;
import com.reconcileai.support.InvestigationTestSupport;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q2.6: a tenant that has run out of gateway budget gets a clean, retryable failure and spends nothing.
 * {@code 429 BUDGET_BLOCKED} is never retried, even when the gateway sends a Retry-After (CONSUMER_CONTRACT.md §4):
 * the budget will not have changed in five seconds. The break stays FAILED_RETRYABLE, so the ordinary re-run path
 * applies once the budget is topped up; no reconciliation step is needed because nothing ran.
 */
class GatewayBudgetIT extends AbstractGatewayIT {

    @Test
    void budgetBlockedIsOneRequestNoSpendAndARetryableBreak() {
        String tenant = "TENANT_GW_BUDGET";
        String analyst = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        gateway.enqueue(new Scripted(429, "BUDGET_BLOCKED", java.util.UUID.randomUUID(), "1"));

        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, jobId);

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_BUDGET_BLOCKED");
        assertThat(job.getString("proposalId")).isNull();
        assertThat(breakStatus(analyst, breakId)).isEqualTo("FAILED_RETRYABLE");

        Map<String, Object> row = jobRow(jobId);
        assertThat(row.get("outcome_unknown")).isEqualTo(Boolean.FALSE);

        List<Call> calls = gateway.calls();
        assertThat(calls).as("Retry-After on BUDGET_BLOCKED must not cause a second request").hasSize(1);
        assertThat(calls.get(0).idempotencyKey()).isEqualTo(keyFor(jobId));
        assertThat(gatewayCalls(jobId)).hasSize(1);
        assertThat(gatewayCalls(jobId).get(0).get("outcome")).isEqualTo("REFUSED");
        assertThat(gatewayCalls(jobId).get(0).get("http_status")).isEqualTo(429);

        // Zero spend recorded on our side: no llm_call for the job, and the failure counter names the reason.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_call WHERE job_id = ?::uuid", Integer.class, jobId))
            .isZero();
        assertThat(failureAudit(tenant, breakId)).contains("\"reason\":\"GATEWAY_BUDGET_BLOCKED\"")
            .doesNotContain("gatewayOutcomeUnknown").doesNotContain("gatewayPolicyBlocked");

        // Budget topped up (empty script): the plain re-run works, with a new job and key, no reconcile needed.
        String rerunId = InvestigationTestSupport.start(analyst, breakId, null);
        assertThat(InvestigationTestSupport.awaitTerminal(analyst, rerunId).getString("status")).isEqualTo("SUCCEEDED");
        assertThat(gateway.calls()).extracting(Call::idempotencyKey).containsExactly(keyFor(jobId), keyFor(rerunId));
    }

    @Test
    void aRateLimitIsAlsoOneRequestAndRetryableLater() {
        String tenant = "TENANT_GW_RATE";
        String analyst = analyst(tenant);
        gateway.enqueue(new Scripted(429, "RATE_LIMITED", java.util.UUID.randomUUID(), "1"));

        JsonPath job = investigate(analyst, "/fixtures/evidence/timing-only.txt");

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_RATE_LIMITED");
        assertThat(gateway.calls()).hasSize(1);
        assertThat(jobRow(job.getString("id")).get("outcome_unknown")).isEqualTo(Boolean.FALSE);
    }
}
