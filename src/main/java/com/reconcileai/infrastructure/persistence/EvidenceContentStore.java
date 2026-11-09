package com.reconcileai.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw bytes of an uploaded evidence file (table evidence_content, V9).
 *
 * <p>Plain JDBC on purpose: JPA would load the blob whenever the row is touched, and nothing here needs dirty checking.
 * Every statement carries the tenant id, and Row-Level Security repeats the check inside the database.
 */
@Repository
public class EvidenceContentStore {

    private final JdbcTemplate jdbc;

    public EvidenceContentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert, or overwrite when a FAILED upload is retried with the same hash. */
    public void save(String tenantId, UUID evidenceId, byte[] content) {
        jdbc.update("""
            INSERT INTO evidence_content (evidence_id, tenant_id, content) VALUES (?, ?, ?)
            ON CONFLICT (evidence_id) DO UPDATE SET content = EXCLUDED.content
            """, evidenceId, tenantId, content);
    }

    public Optional<byte[]> find(String tenantId, UUID evidenceId) {
        List<byte[]> rows = jdbc.query(
            "SELECT content FROM evidence_content WHERE evidence_id = ? AND tenant_id = ?",
            (rs, i) -> rs.getBytes("content"), evidenceId, tenantId);
        return rows.stream().findFirst();
    }
}
