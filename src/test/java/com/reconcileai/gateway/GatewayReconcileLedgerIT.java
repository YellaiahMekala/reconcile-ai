package com.reconcileai.gateway;

import com.reconcileai.support.AbstractGatewayIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.FakeGatewayServer.Scripted;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Reconciling an unknown gateway outcome with an invocation id: reconcile-ai reads the gateway ledger itself
 * ({@code GET /api/v1/invocations/{id}} with the tenant's token) and records what it says, instead of trusting the
 * person to have looked. A ticket reference still works without a read. Nothing is recorded when the ledger
 * cannot be read or the id does not belong to the job.
 */
class GatewayReconcileLedgerIT extends AbstractGatewayIT {

    private record Unknown(String tenant, String analyst, String breakId, String jobId, UUID invocationId) { }

    private Unknown unknownOutcomeJob(String tenant) {
        String analyst = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(analyst, "30500.00", "30000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        UUID invocation = UUID.randomUUID();
        gateway.enqueue(new Scripted(504, "UNKNOWN_OUTCOME", invocation, null));
        String jobId = InvestigationTestSupport.start(analyst, breakId, null);
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, jobId);
        assertThat(job.getString("failureReason")).isEqualTo("GATEWAY_UNKNOWN_OUTCOME");
        assertThat(job.getBoolean("outcomeUnknown")).isTrue();
        return new Unknown(tenant, analyst, breakId, jobId, invocation);
    }

    private static String approverL2(String tenant) {
        return JwtTestSupport.bearer("gw-approver-l2", tenant, List.of("APPROVER_L2"));
    }

    private io.restassured.response.ValidatableResponse reconcile(String bearer, String jobId, String reference) {
        return given().header("Authorization", bearer).contentType("application/json")
            .body(Map.of("reference", reference))
            .when().post("/api/v1/investigations/" + jobId + "/reconcile").then();
    }

    private Map<String, Object> reconcileColumns(String jobId) {
        return jdbc.queryForMap("SELECT reconciled_at, reconcile_ref, ledger_execution_certainty, ledger_status "
            + "FROM investigation_job WHERE id = ?::uuid", jobId);
    }

    private String reconcileAudit(String tenant, String breakId) {
        List<String> rows = jdbc.queryForList("SELECT details FROM audit_event WHERE tenant_id = ? "
            + "AND aggregate_id = ?::uuid AND event_type = 'INVESTIGATION_OUTCOME_RECONCILED'", String.class, tenant, breakId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    @Test
    void invocationIdReferenceIsCheckedAgainstTheLedgerAndRecorded() {
        Unknown u = unknownOutcomeJob("TENANT_GW_LEDGER_OK");

        reconcile(approverL2(u.tenant()), u.jobId(), u.invocationId().toString())
            .statusCode(200)
            .body("reconcileRef", equalTo(u.invocationId().toString()))
            .body("ledgerExecutionCertainty", equalTo("UNKNOWN"))
            .body("ledgerStatus", equalTo("UNKNOWN_OUTCOME"))
            .body("reconciledBy", equalTo("gw-approver-l2"));

        assertThat(gateway.ledgerReads()).containsExactly(u.invocationId().toString());
        Map<String, Object> row = reconcileColumns(u.jobId());
        assertThat(row.get("reconciled_at")).isNotNull();
        assertThat(row.get("ledger_execution_certainty")).isEqualTo("UNKNOWN");
        assertThat(reconcileAudit(u.tenant(), u.breakId()))
            .contains("\"ledgerChecked\":true")
            .contains("\"ledgerExecutionCertainty\":\"UNKNOWN\"");

        // The block is lifted: the re-run is accepted.
        InvestigationTestSupport.start(u.analyst(), u.breakId(), null);
    }

    @Test
    void anInvocationIdThatIsNotThisJobsIsRefusedWithoutAnyRead() {
        Unknown u = unknownOutcomeJob("TENANT_GW_LEDGER_FOREIGN");

        reconcile(approverL2(u.tenant()), u.jobId(), UUID.randomUUID().toString())
            .statusCode(409).body("code", equalTo("INVOCATION_NOT_FOR_JOB"));

        assertThat(gateway.ledgerReads()).isEmpty();
        assertThat(reconcileColumns(u.jobId()).get("reconciled_at")).isNull();
    }

    @Test
    void anIdTheLedgerDoesNotKnowIsRefused() {
        Unknown u = unknownOutcomeJob("TENANT_GW_LEDGER_MISSING");
        gateway.forget(u.invocationId());

        reconcile(approverL2(u.tenant()), u.jobId(), u.invocationId().toString())
            .statusCode(409).body("code", equalTo("LEDGER_ENTRY_NOT_FOUND"));

        assertThat(reconcileColumns(u.jobId()).get("reconciled_at")).isNull();
    }

    @Test
    void ledgerDownIs503AndNothingIsRecorded() {
        Unknown u = unknownOutcomeJob("TENANT_GW_LEDGER_DOWN");
        gateway.ledgerDown(true);

        reconcile(approverL2(u.tenant()), u.jobId(), u.invocationId().toString())
            .statusCode(503).header("Retry-After", "30").body("code", equalTo("GATEWAY_LEDGER_UNAVAILABLE"));

        assertThat(reconcileColumns(u.jobId()).get("reconciled_at")).isNull();
        assertThat(reconcileAudit(u.tenant(), u.breakId())).isEmpty();
        given().header("Authorization", u.analyst()).contentType("application/json").body("{}")
            .when().post("/api/v1/breaks/" + u.breakId() + "/investigations")
            .then().statusCode(409).body("code", equalTo("OUTCOME_UNRECONCILED"));

        // A ticket reference is the documented way out while the ledger is down.
        reconcile(approverL2(u.tenant()), u.jobId(), "INC-20261116-7")
            .statusCode(200)
            .body("reconcileRef", equalTo("INC-20261116-7"))
            .body("ledgerExecutionCertainty", nullValue());
        assertThat(reconcileAudit(u.tenant(), u.breakId())).contains("\"ledgerChecked\":false");
    }

    @Test
    void theStarterCannotReconcileEvenWithAValidInvocationId() {
        Unknown u = unknownOutcomeJob("TENANT_GW_LEDGER_SELF");
        String sameSubject = JwtTestSupport.bearer("gw-analyst", u.tenant(), List.of("APPROVER_L2"));

        reconcile(sameSubject, u.jobId(), u.invocationId().toString())
            .statusCode(409).body("code", equalTo("RECONCILER_MUST_DIFFER"));
        assertThat(gateway.ledgerReads()).as("refused before any ledger read").isEmpty();
    }

    @Test
    void gatewayCallsViewShowsOurRowWithTheLedgerAnswerAndIsRoleAndTenantScoped() {
        Unknown u = unknownOutcomeJob("TENANT_GW_LEDGER_VIEW");
        String path = "/api/v1/investigations/" + u.jobId() + "/gateway-calls";

        JsonPath view = given().header("Authorization", approverL2(u.tenant())).when().get(path)
            .then().statusCode(200).extract().jsonPath();
        assertThat(view.getList("$")).hasSize(1);
        assertThat(view.getString("[0].outcome")).isEqualTo("UNKNOWN");
        assertThat(view.getString("[0].invocationId")).isEqualTo(u.invocationId().toString());
        assertThat(view.getString("[0].ledgerResult")).isEqualTo("FOUND");
        assertThat(view.getString("[0].ledgerExecutionCertainty")).isEqualTo("UNKNOWN");

        given().header("Authorization", u.analyst()).when().get(path).then().statusCode(403);
        given().header("Authorization", approverL2("TENANT_GW_LEDGER_OTHER")).when().get(path)
            .then().statusCode(404);
    }
}
