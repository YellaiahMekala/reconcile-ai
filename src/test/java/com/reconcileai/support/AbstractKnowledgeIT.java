// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.support;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.List;
import java.util.Map;

/**
 * Base for the platform Q1 integration tests: the real application and Postgres, with knowledge on and EKA plus the
 * realm token endpoint played by {@link FakeEkaServer}. Two tenants, each with its own client and secret variable,
 * exactly as the platform realm maps {@code svc-reconcile-tenant-a/b}.
 */
public abstract class AbstractKnowledgeIT extends AbstractPostgresIT {

    protected static final String TENANT_A = "TENANT_KA";
    protected static final String TENANT_B = "TENANT_KB";
    /** Has no knowledge client configured. */
    protected static final String TENANT_NONE = "TENANT_KNONE";

    @LocalServerPort
    int port;

    protected final JdbcTemplate jdbc = adminJdbc();
    protected final FakeEkaServer eka = FakeEkaServer.get();

    protected static void register(DynamicPropertyRegistry r, boolean failClosed) {
        FakeEkaServer fake = FakeEkaServer.get();
        r.add("reconcile.knowledge.enabled", () -> "true");
        r.add("reconcile.knowledge.base-url", fake::baseUrl);
        r.add("reconcile.knowledge.token-uri", () -> fake.baseUrl() + "/token");
        r.add("reconcile.knowledge.request-timeout", () -> "1s");
        r.add("reconcile.knowledge.fail-closed", () -> String.valueOf(failClosed));
        r.add("reconcile.knowledge.breaker-window", () -> "1000");      // keep the breaker out of these tests
        r.add("reconcile.knowledge.tenants.[" + TENANT_A + "].client-id", () -> "svc-ka");
        r.add("reconcile.knowledge.tenants.[" + TENANT_A + "].client-secret-env", () -> "TEST_KNOWLEDGE_SECRET_A");
        r.add("reconcile.knowledge.tenants.[" + TENANT_B + "].client-id", () -> "svc-kb");
        r.add("reconcile.knowledge.tenants.[" + TENANT_B + "].client-secret-env", () -> "TEST_KNOWLEDGE_SECRET_B");
        r.add("TEST_KNOWLEDGE_SECRET_A", () -> "synthetic-secret-a");
        r.add("TEST_KNOWLEDGE_SECRET_B", () -> "synthetic-secret-b");
    }

    @BeforeEach
    void resetEka() {
        RestAssured.port = port;
        eka.reset();
        eka.mapClient("svc-ka", TENANT_A);
        eka.mapClient("svc-kb", TENANT_B);
    }

    protected static String analyst(String tenant) {
        return JwtTestSupport.bearer("kb-analyst", tenant, List.of("RECON_ANALYST"));
    }

    protected JsonPath investigate(String tenant, String fixture) {
        String bearer = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(bearer, "30500.00", "30000.00");
        ApiTestSupport.upload(bearer, breakId, fixture, "text/plain");
        return InvestigationTestSupport.awaitTerminal(bearer, InvestigationTestSupport.start(bearer, breakId, null));
    }

    protected String breakStatus(String tenant, String breakId) {
        return RestAssured.given().header("Authorization", analyst(tenant)).get("/api/v1/breaks/" + breakId)
            .then().statusCode(200).extract().path("status");
    }

    protected Map<String, Object> knowledgeRow(String jobId) {
        return jdbc.queryForMap("SELECT knowledge_outcome, knowledge_reason, knowledge_refs, retrieved_chunk_ids "
            + "FROM investigation_job WHERE id = ?::uuid", jobId);
    }

    protected List<String> knowledgeAudit(String tenant) {
        return jdbc.queryForList("SELECT details FROM audit_event WHERE tenant_id = ? "
            + "AND event_type = 'KNOWLEDGE_CONSULTED' ORDER BY sequence", String.class, tenant);
    }
}
