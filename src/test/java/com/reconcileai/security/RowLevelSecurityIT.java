// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.security;

import com.reconcileai.infrastructure.security.TenantScope;
import com.reconcileai.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-level tenant isolation (V6). Every query below deliberately OMITS a tenant filter: the point is to prove
 * that Postgres itself enforces isolation when application code forgets to.
 */
class RowLevelSecurityIT extends AbstractPostgresIT {

    @Autowired
    JdbcTemplate app; // the application's own (restricted, tenant-bound) connection pool

    final String run = UUID.randomUUID().toString().substring(0, 8);
    final String tenantA = "RLS_A_" + run;
    final String tenantB = "RLS_B_" + run;

    @BeforeEach
    void seed() {
        for (String t : new String[] {tenantA, tenantB}) {
            adminJdbc().update("""
                INSERT INTO break_item (id, tenant_id, break_reference, status, ledger_amount, external_amount,
                                        currency_code, source, created_at, updated_at)
                VALUES (?, ?, ?, 'OPEN', 10, 5, 'USD', 'REST', now(), now())""",
                UUID.randomUUID(), t, "RLS-" + run);
            adminJdbc().update("INSERT INTO vector_store (content, metadata) VALUES (?, ?::json)",
                "statement for " + t, "{\"tenantId\":\"" + t + "\",\"breakItemId\":\"x\"}");
        }
    }

    private long breaksWithRunReference() {
        return app.queryForObject("SELECT count(*) FROM break_item WHERE break_reference = ?", Long.class, "RLS-" + run);
    }

    @Test
    void unscopedConnectionSeesNothing() {
        assertThat(breaksWithRunReference()).isZero();
        assertThat(app.queryForObject("SELECT count(*) FROM vector_store", Long.class)).isZero();
    }

    @Test
    void tenantScopeSeesOnlyItsOwnRowsEvenWithoutATenantFilter() {
        assertThat(TenantScope.call(tenantA, this::breaksWithRunReference)).isEqualTo(1);
        String owner = TenantScope.call(tenantA, () -> app.queryForObject(
            "SELECT tenant_id FROM break_item WHERE break_reference = ?", String.class, "RLS-" + run));
        assertThat(owner).isEqualTo(tenantA);

        // vector leg data is policed too (policy on metadata->>'tenantId')
        assertThat(TenantScope.call(tenantB, () -> app.queryForList(
            "SELECT metadata->>'tenantId' FROM vector_store WHERE content LIKE ?", String.class, "%" + run)))
            .containsExactly(tenantB);
    }

    @Test
    void writingARowForAnotherTenantIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> TenantScope.run(tenantA, () -> app.update("""
                INSERT INTO break_item (id, tenant_id, break_reference, status, ledger_amount, external_amount,
                                        currency_code, source, created_at, updated_at)
                VALUES (?, ?, 'SMUGGLED', 'OPEN', 1, 1, 'USD', 'REST', now(), now())""", UUID.randomUUID(), tenantB)))
            .rootCause().hasMessageContaining("row-level security");

        // and an UPDATE cannot touch the other tenant's rows (0 rows affected, not an error)
        int touched = TenantScope.call(tenantA, () -> app.update(
            "UPDATE break_item SET status = 'RESOLVED' WHERE tenant_id = ?", tenantB));
        assertThat(touched).isZero();
    }

    @Test
    void systemScopeSeesAllTenantsAndAJwtCannotClaimIt() {
        assertThat(TenantScope.callAsSystem(this::breaksWithRunReference)).isEqualTo(2);

        Jwt forged = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "x")
            .claim("tenant_id", TenantScope.SYSTEM).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(forged));
        try {
            assertThat(TenantScope.current()).isEmpty();
            assertThat(breaksWithRunReference()).isZero();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void appRoleCannotSwitchRlsOff() {
        assertThatThrownBy(() -> app.execute("ALTER TABLE break_item DISABLE ROW LEVEL SECURITY"))
            .rootCause().hasMessageContaining("must be owner");
        assertThat(app.queryForObject("SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = current_user",
            Boolean.class)).isFalse();
    }
}
