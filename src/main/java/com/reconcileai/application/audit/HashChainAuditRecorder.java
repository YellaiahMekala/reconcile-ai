package com.reconcileai.application.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Tamper-evident audit trail.
 *
 * <h2>Write path (inside the caller's transaction)</h2>
 * <pre>
 *  INSERT audit_chain_head(tenant, 0, GENESIS) ON CONFLICT DO NOTHING    first event for a tenant
 *  SELECT last_sequence, last_hash FROM audit_chain_head ... FOR UPDATE  serialise appends for THIS tenant only
 *  hash = SHA-256(prev_hash | ... | canonical details | occurred_at)
 *  INSERT audit_event(sequence = last+1, prev_hash, hash)
 *  UPDATE audit_chain_head SET last_sequence, last_hash
 * </pre>
 * The audit row commits if and only if the business change commits (same transaction, JDBC shares the JPA
 * connection). A DB trigger rejects UPDATE/DELETE on audit_event; the hash chain detects anything done around it
 * (e.g. by a DBA who disables the trigger).
 */
@Component
public class HashChainAuditRecorder implements AuditRecorder {

    private final JdbcTemplate jdbc;

    public HashChainAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AuditEntry e) {
        jdbc.update("INSERT INTO audit_chain_head (tenant_id, last_sequence, last_hash) VALUES (?, 0, ?) "
            + "ON CONFLICT (tenant_id) DO NOTHING", e.tenantId(), AuditHashing.GENESIS);

        Map<String, Object> head = jdbc.queryForMap(
            "SELECT last_sequence, last_hash FROM audit_chain_head WHERE tenant_id = ? FOR UPDATE", e.tenantId());
        long sequence = ((Number) head.get("last_sequence")).longValue() + 1;
        String prevHash = (String) head.get("last_hash");

        Instant occurredAt = AuditHashing.normalize(Instant.now());
        String details = AuditHashing.canonicalDetails(e.details());
        String hash = AuditHashing.hash(prevHash, e.tenantId(), sequence, e.eventType(), e.aggregateType(),
            e.aggregateId(), e.actor(), details, occurredAt);

        jdbc.update("""
            INSERT INTO audit_event (id, tenant_id, sequence, event_type, aggregate_type, aggregate_id, actor, details,
                                     occurred_at, prev_hash, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), e.tenantId(), sequence, e.eventType(), e.aggregateType(), e.aggregateId(),
            e.actor(), details, Timestamp.from(occurredAt), prevHash, hash);

        jdbc.update("UPDATE audit_chain_head SET last_sequence = ?, last_hash = ? WHERE tenant_id = ?",
            sequence, hash, e.tenantId());
    }
}
