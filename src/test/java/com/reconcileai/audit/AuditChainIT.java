package com.reconcileai.audit;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Increment 9 gate: the audit trail is append-only, hash-chained per tenant, and tampering is detectable. */
class AuditChainIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

        final JdbcTemplate jdbc = adminJdbc();

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void eventsAreChainedWithGaplessPerTenantSequences() {
        String tenant = "TENANT_AUDIT_CHAIN";
        String analyst = JwtTestSupport.bearer("audit-a", tenant, List.of("RECON_ANALYST"));
        for (int i = 0; i < 3; i++) {
            ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT sequence, prev_hash, hash FROM audit_event WHERE tenant_id = ? ORDER BY sequence", tenant);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("sequence")).isEqualTo(1L);
        assertThat(rows.get(0).get("prev_hash")).isEqualTo("0".repeat(64));
        assertThat(rows.get(1).get("prev_hash")).isEqualTo(rows.get(0).get("hash"));
        assertThat(rows.get(2).get("prev_hash")).isEqualTo(rows.get(1).get("hash"));

        String sre = JwtTestSupport.bearer("audit-sre", tenant, List.of("SRE_ADMIN"));
        given().header("Authorization", sre).when().get("/api/v1/audit/verify").then().statusCode(200)
            .body("valid", equalTo(true)).body("eventsChecked", equalTo(3)).body("firstBrokenSequence", nullValue());
    }

    @Test
    void databaseRejectsUpdateAndDelete() {
        String tenant = "TENANT_AUDIT_RO";
        ApiTestSupport.createBreak(JwtTestSupport.bearer("ro-a", tenant, List.of("RECON_ANALYST")), "10.00", "9.00");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET actor = 'mallory' WHERE tenant_id = ?", tenant))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE tenant_id = ?", tenant))
            .hasMessageContaining("append-only");
    }

    @Test
    void tamperingBehindTheTriggerIsDetectedByVerification() {
        String tenant = "TENANT_AUDIT_TAMPER";
        String analyst = JwtTestSupport.bearer("tamper-a", tenant, List.of("RECON_ANALYST"));
        ApiTestSupport.createBreak(analyst, "10.00", "9.00");
        ApiTestSupport.createBreak(analyst, "20.00", "9.00");

        // Simulate a privileged actor who bypasses the append-only trigger.
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER trg_audit_event_append_only");
        try {
            jdbc.update("UPDATE audit_event SET actor = 'mallory' WHERE tenant_id = ? AND sequence = 1", tenant);
        } finally {
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER trg_audit_event_append_only");
        }

        String sre = JwtTestSupport.bearer("tamper-sre", tenant, List.of("SRE_ADMIN"));
        given().header("Authorization", sre).when().get("/api/v1/audit/verify").then().statusCode(200)
            .body("valid", equalTo(false)).body("firstBrokenSequence", equalTo(1));
    }

    @Test
    void verifyIsSreOnlyAndTenantScoped() {
        String analyst = JwtTestSupport.bearer("scope-a", "TENANT_AUDIT_SCOPE", List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "10.00", "9.00");

        given().header("Authorization", analyst).when().get("/api/v1/audit/verify").then().statusCode(403);

        // another tenant sees no events for this break id (not a 403 that would confirm it exists)
        String foreign = JwtTestSupport.bearer("scope-x", "TENANT_AUDIT_SCOPE_OTHER", List.of("SRE_ADMIN"));
        given().header("Authorization", foreign).when().get("/api/v1/audit/aggregates/" + breakId)
            .then().statusCode(200).body("size()", equalTo(0));
        given().header("Authorization", analyst).when().get("/api/v1/audit/aggregates/" + breakId)
            .then().statusCode(200).body("size()", equalTo(1));
    }
}
