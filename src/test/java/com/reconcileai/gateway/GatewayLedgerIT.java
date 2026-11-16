package com.reconcileai.gateway;

import com.reconcileai.support.AbstractGatewayIT;
import com.reconcileai.support.FakeGatewayServer.Call;
import com.reconcileai.support.FakeGatewayServer.Scripted;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Platform Q2.4: every terminal path of a gateway call can be reconciled as
 * {@code jobId -> idempotencyKey -> invocationId} against the gateway ledger ({@code GET /api/v1/invocations/{id}}),
 * from our own {@code gateway_call} table. A successful call also lands in {@code llm_call} as model
 * {@code ai-gateway}, so per-tenant cost reporting stays truthful on this route.
 */
class GatewayLedgerIT extends AbstractGatewayIT {

    @Test
    void aSuccessfulCallIsLinkedJobToKeyToInvocationAndCountedAsAnLlmCall() {
        String tenant = "TENANT_GW_LEDGER_OK";
        String analyst = analyst(tenant);

        JsonPath job = investigate(analyst, "/fixtures/evidence/timing-only.txt");
        String jobId = job.getString("id");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        List<Call> calls = gateway.calls();
        assertThat(calls).hasSize(1);
        Call call = calls.get(0);
        assertThat(call.idempotencyKey()).isEqualTo(keyFor(jobId));
        assertThat(call.authorization()).startsWith("Bearer svc-token-");

        List<Map<String, Object>> rows = gatewayCalls(jobId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("attempt")).isEqualTo(1);
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(row.get("gateway_status")).isEqualTo("OK");
        assertThat(uuid(row.get("invocation_id"))).isEqualTo(call.invocationId());
        assertThat((Integer) row.get("input_tokens")).isPositive();
        assertThat((Integer) row.get("output_tokens")).isPositive();

        // The link resolves on the gateway side.
        given().when().get(gateway.baseUrl() + "/api/v1/invocations/" + call.invocationId())
            .then().statusCode(200)
            .body("invocationId", equalTo(call.invocationId().toString()))
            .body("status", equalTo("OK"))
            .body("executionCertainty", equalTo("EXECUTED"));

        // Cost reporting: one llm_call row for the job, attributed to the gateway, with the gateway's token counts.
        List<Map<String, Object>> llm = jdbc.queryForList(
            "SELECT model, prompt_tokens, completion_tokens, outcome FROM llm_call WHERE job_id = ?::uuid", jobId);
        assertThat(llm).hasSize(1);
        assertThat(llm.get(0).get("model")).isEqualTo("ai-gateway");
        assertThat(llm.get(0).get("outcome")).isEqualTo("OK");
        assertThat(llm.get(0).get("prompt_tokens")).isEqualTo(row.get("input_tokens"));
        assertThat(llm.get(0).get("completion_tokens")).isEqualTo(row.get("output_tokens"));

        // The service token asked for the gateway audience (platform realm, spike S-3).
        assertThat(gateway.tokenForms()).isNotEmpty();
        assertThat(gateway.tokenForms().get(gateway.tokenForms().size() - 1)).contains("scope=aud-ai-gateway");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"429, BUDGET_BLOCKED", "422, RISK_BLOCKED", "422, SENSITIVITY_BLOCKED"})
    void aRefusalWithALedgerRowKeepsTheInvocationId(int status, String code) {
        String tenant = "TENANT_GW_LEDGER_" + code;
        String analyst = analyst(tenant);
        UUID invocation = UUID.randomUUID();
        gateway.enqueue(new Scripted(status, code, invocation, null));

        JsonPath job = investigate(analyst, "/fixtures/evidence/timing-only.txt");
        String jobId = job.getString("id");

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_" + code);
        List<Map<String, Object>> rows = gatewayCalls(jobId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("outcome")).isEqualTo("REFUSED");
        assertThat(rows.get(0).get("http_status")).isEqualTo(status);
        assertThat(rows.get(0).get("code")).isEqualTo(code);
        assertThat(uuid(rows.get(0).get("invocation_id"))).isEqualTo(invocation);
        assertThat(gateway.calls()).extracting(Call::idempotencyKey).containsExactly(keyFor(jobId));
        given().when().get(gateway.baseUrl() + "/api/v1/invocations/" + invocation)
            .then().statusCode(200).body("status", equalTo(code)).body("executionCertainty", equalTo("NOT_EXECUTED"));
        assertThat(failureAudit(tenant, breakIdOf(jobId))).contains("\"gatewayInvocationId\":\"" + invocation + "\"");

        // Nothing ran, so nothing is billed: no llm_call row for this job.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_call WHERE job_id = ?::uuid", Integer.class, jobId))
            .isZero();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"401, UNAUTHENTICATED", "400, INVALID_REQUEST"})
    void aRejectionBeforeTheLedgerHasNoInvocationIdButIsStillLogged(int status, String code) {
        String tenant = "TENANT_GW_LEDGER_" + code;
        String analyst = analyst(tenant);
        gateway.enqueue(Scripted.rejection(status, code));

        JsonPath job = investigate(analyst, "/fixtures/evidence/timing-only.txt");
        String jobId = job.getString("id");

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_" + code);
        List<Map<String, Object>> rows = gatewayCalls(jobId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("outcome")).isEqualTo("REFUSED");
        assertThat(rows.get(0).get("invocation_id")).isNull();
        assertThat(jobRow(jobId).get("outcome_unknown")).isEqualTo(Boolean.FALSE);
        assertThat(failureAudit(tenant, breakIdOf(jobId))).doesNotContain("gatewayInvocationId");
    }

    @Test
    void anIdNobodyIssuedIsNotInTheLedger() {
        given().when().get(gateway.baseUrl() + "/api/v1/invocations/" + UUID.randomUUID()).then().statusCode(404);
    }

    private String breakIdOf(String jobId) {
        return jdbc.queryForObject("SELECT break_item_id FROM investigation_job WHERE id = ?::uuid", String.class, jobId);
    }
}
