// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.audit;

import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped reads of the audit chain plus full-chain verification. Tenant comes only from the JWT. */
@Service
public class AuditQueryService {

    /** Verification streams in pages so a long chain never loads into memory at once. */
    static final int PAGE = 500;

    public record AuditEventView(long sequence, String eventType, String aggregateType, UUID aggregateId, String actor,
                                 String details, Instant occurredAt, String prevHash, String hash) {}

    /**
     * @param valid              true when every row's prev_hash links to its predecessor and its hash recomputes
     * @param firstBrokenSequence null when valid; otherwise the first sequence that fails (gap, relink, or rehash)
     */
    public record ChainVerification(boolean valid, long eventsChecked, Long firstBrokenSequence, String headHash) {}

    private static final RowMapper<AuditEventView> MAPPER = (rs, i) -> new AuditEventView(
        rs.getLong("sequence"), rs.getString("event_type"), rs.getString("aggregate_type"),
        rs.getObject("aggregate_id", UUID.class), rs.getString("actor"), rs.getString("details"),
        rs.getTimestamp("occurred_at").toInstant(), rs.getString("prev_hash"), rs.getString("hash"));

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    public AuditQueryService(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> forAggregate(UUID aggregateId) {
        return jdbc.query("SELECT * FROM audit_event WHERE tenant_id = ? AND aggregate_id = ? ORDER BY sequence",
            MAPPER, tenantContext.requireTenantId(), aggregateId);
    }

    @Transactional(readOnly = true)
    public ChainVerification verifyCurrentTenant() {
        return verify(tenantContext.requireTenantId());
    }

    /** Package-visible for tests; production callers go through verifyCurrentTenant(). */
    ChainVerification verify(String tenantId) {
        String expectedPrev = AuditHashing.GENESIS;
        long expectedSeq = 1;
        long checked = 0;
        while (true) {
            List<AuditEventView> page = jdbc.query(
                "SELECT * FROM audit_event WHERE tenant_id = ? AND sequence >= ? ORDER BY sequence LIMIT ?",
                MAPPER, tenantId, expectedSeq, PAGE);
            if (page.isEmpty()) {
                return new ChainVerification(true, checked, null, expectedPrev);
            }
            for (AuditEventView ev : page) {
                String recomputed = AuditHashing.hash(ev.prevHash(), tenantId, ev.sequence(), ev.eventType(),
                    ev.aggregateType(), ev.aggregateId(), ev.actor(), ev.details(), ev.occurredAt());
                boolean ok = ev.sequence() == expectedSeq          // no deleted rows (gap)
                    && ev.prevHash().equals(expectedPrev)          // no re-linked / inserted rows
                    && recomputed.equals(ev.hash());               // no edited content
                if (!ok) {
                    return new ChainVerification(false, checked, expectedSeq, null);
                }
                expectedPrev = ev.hash();
                expectedSeq++;
                checked++;
            }
        }
    }
}
