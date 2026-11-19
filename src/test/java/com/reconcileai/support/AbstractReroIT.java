// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

/**
 * Base for the platform Q3 (RERO) integration tests: the real application and Postgres, RERO mode on, RERO and the
 * realm token endpoint played by {@link FakeReroServer}, run tokens signed with an ephemeral key
 * ({@link RunTokenTestKeys}). Two tenants, each with its own client and secret variable. No external service and no
 * cost; the default build runs these.
 */
public abstract class AbstractReroIT extends AbstractPostgresIT {

    protected static final String TENANT_A = "TENANT_RA";
    protected static final String TENANT_B = "TENANT_RB";
    /** Only for the audience test: its cached service token must not be shared with other tests. */
    protected static final String TENANT_C = "TENANT_RC";

    @LocalServerPort
    protected int port;

    protected final JdbcTemplate jdbc = adminJdbc();
    protected final FakeReroServer rero = FakeReroServer.get();

    @DynamicPropertySource
    static void reroProps(DynamicPropertyRegistry r) {
        register(r);
    }

    /** RERO on, pointed at {@link FakeReroServer}; also used by ITs with another base class (gateway governance). */
    public static void register(DynamicPropertyRegistry r) {
        FakeReroServer fake = FakeReroServer.get();
        r.add("reconcile.rero.enabled", () -> "true");
        r.add("reconcile.rero.base-url", fake::baseUrl);
        r.add("reconcile.rero.token-uri", () -> fake.baseUrl() + "/token");
        r.add("reconcile.rero.request-timeout", () -> "2s");
        r.add("reconcile.rero.poll-interval", () -> "100ms");
        r.add("reconcile.rero.deadline-seconds", () -> "5");
        r.add("reconcile.rero.deadline-grace", () -> "1s");
        r.add("reconcile.rero.max-tool-calls", () -> "8");
        r.add("reconcile.rero.run-token.kid", () -> RunTokenTestKeys.KID);
        r.add("reconcile.rero.run-token.private-key-pem", RunTokenTestKeys::privatePem);
        r.add("reconcile.rero.run-token.public-key-pem", RunTokenTestKeys::publicPem);
        r.add("reconcile.rero.tenants.[" + TENANT_A + "].client-id", () -> "svc-rero-a");
        r.add("reconcile.rero.tenants.[" + TENANT_A + "].client-secret-env", () -> "TEST_RERO_SECRET_A");
        r.add("reconcile.rero.tenants.[" + TENANT_B + "].client-id", () -> "svc-rero-b");
        r.add("reconcile.rero.tenants.[" + TENANT_B + "].client-secret-env", () -> "TEST_RERO_SECRET_B");
        r.add("reconcile.rero.tenants.[" + TENANT_C + "].client-id", () -> "svc-rero-c");
        r.add("reconcile.rero.tenants.[" + TENANT_C + "].client-secret-env", () -> "TEST_RERO_SECRET_C");
        r.add("TEST_RERO_SECRET_C", () -> "synthetic-secret-c");
        r.add("TEST_RERO_SECRET_A", () -> "synthetic-secret-a");
        r.add("TEST_RERO_SECRET_B", () -> "synthetic-secret-b");
    }

    @BeforeEach
    void resetRero() {
        RestAssured.port = port;
        rero.reset();
        rero.mapClient("svc-rero-a", TENANT_A);
        rero.mapClient("svc-rero-b", TENANT_B);
        rero.mapClient("svc-rero-c", TENANT_C);
        rero.internalBaseUrl("http://127.0.0.1:" + port);
    }

    protected static String analyst(String tenant) {
        return JwtTestSupport.bearer("rero-analyst", tenant, List.of("RECON_ANALYST"));
    }

    /** A break with one READY evidence file; returns the break id. */
    protected String breakWithEvidence(String tenant, String fixture) {
        String bearer = analyst(tenant);
        String breakId = ApiTestSupport.createBreak(bearer, "30500.00", "30000.00");
        ApiTestSupport.upload(bearer, breakId, fixture, "text/plain");
        return breakId;
    }

    /**
     * A RUNNING job row inserted directly (owner connection), so the internal API can be probed without a live RERO
     * run. The job is owned by a node that does not exist, which keeps the real runner away from it.
     */
    protected UUID runningJob(String tenant, String mode, Integer maxToolCalls) {
        String breakId = breakWithEvidence(tenant, "/fixtures/evidence/fee-reviewer-challenge.txt");
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO investigation_job (id, tenant_id, break_item_id, status, stage, initiated_by, mode, created_at,
                                           started_at, heartbeat_at, owner_node, tool_calls_max)
            VALUES (?, ?, ?::uuid, 'RUNNING', 'CALLING_MODEL', 'rero-analyst', ?, now(), now(), now(), 'test-node', ?)""",
            jobId, tenant, breakId, mode, maxToolCalls);
        return jobId;
    }

    protected void setStatus(UUID jobId, String status) {
        jdbc.update("UPDATE investigation_job SET status = ?, finished_at = now() WHERE id = ?", status, jobId);
    }

    protected int toolCallsUsed(UUID jobId) {
        return jdbc.queryForObject("SELECT tool_calls_used FROM investigation_job WHERE id = ?", Integer.class, jobId);
    }
}
