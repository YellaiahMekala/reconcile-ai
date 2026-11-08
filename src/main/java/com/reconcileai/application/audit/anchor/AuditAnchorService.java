package com.reconcileai.application.audit.anchor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.audit.AuditHashing;
import com.reconcileai.infrastructure.security.TenantScope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Anchors each tenant's audit-chain head in write-once external storage, and verifies the chain against those anchors.
 *
 * <h2>The gap this closes</h2>
 * The hash chain (V4) detects edits, but an attacker with database-owner rights could in principle rewrite the whole
 * chain consistently, disable the append-only trigger, and recompute every hash. Periodically publishing
 * {@code (tenant, sequence, headHash)}, signed, to storage the database cannot touch (S3 Object Lock) means a rewrite
 * of any anchored history produces a different hash at that sequence than the immutable copy holds.
 *
 * <h2>Honest scope</h2>
 * Events appended after the latest anchor are not yet externally protected (the exposure window is the anchor interval).
 * It proves the chain up to each anchored sequence was what it was at anchoring time; it does not prove the events were
 * truthful when written. COMPLIANCE-mode retention cannot be shortened by anyone, so size the retention deliberately.
 */
public class AuditAnchorService {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorService.class);

    public record AnchorView(long anchoredSequence, String headHash, String objectKey, String objectVersionId,
                             String keyId, Instant anchoredAt, Instant retainUntil) {}

    /** @param problem null when valid, otherwise the first failure found (which anchor and why) */
    public record AnchorVerification(boolean valid, int anchorsChecked, Long firstBadSequence, String problem) {}

    private static final RowMapper<AnchorView> VIEW = (rs, i) -> new AnchorView(rs.getLong("anchored_sequence"),
        rs.getString("head_hash"), rs.getString("object_key"), rs.getString("object_version_id"), rs.getString("key_id"),
        rs.getTimestamp("anchored_at").toInstant(), rs.getTimestamp("retain_until").toInstant());

    private final JdbcTemplate jdbc;
    private final AnchorStore store;
    private final AnchorSigner signer;
    private final Duration retention;
    private final MeterRegistry meters;
    private final ObjectMapper json = new ObjectMapper();

    public AuditAnchorService(JdbcTemplate jdbc, AnchorStore store, AnchorSigner signer, Duration retention, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.store = store;
        this.signer = signer;
        this.retention = retention;
        this.meters = meters;
    }

    /** Anchors every tenant whose chain moved since its last anchor. @return number of anchors written */
    public int anchorAll() {
        List<String> tenants = TenantScope.callAsSystem(() -> jdbc.queryForList("SELECT tenant_id FROM audit_chain_head ORDER BY 1", String.class));
        int written = 0;
        for (String tenant : tenants) {
            try {
                if (TenantScope.call(tenant, () -> anchorTenant(tenant))) {
                    written++;
                }
            } catch (RuntimeException e) { // one tenant's storage error must not stop the others
                meters.counter("reconcile.audit.anchor", "result", "error").increment();
                log.warn("audit anchor failed for tenant {}: {}", tenant, e.toString());
            }
        }
        return written;
    }

    /** Must run inside the tenant's scope. @return true if a new anchor was written */
    boolean anchorTenant(String tenant) {
        var head = jdbc.queryForMap("SELECT last_sequence, last_hash FROM audit_chain_head WHERE tenant_id = ?", tenant);
        long sequence = ((Number) head.get("last_sequence")).longValue();
        String headHash = ((String) head.get("last_hash")).trim();
        Long last = jdbc.queryForObject("SELECT COALESCE(MAX(anchored_sequence), 0) FROM audit_anchor WHERE tenant_id = ?", Long.class, tenant);
        if (sequence <= last) {
            return false;
        }
        Instant now = AuditHashing.normalize(Instant.now());
        Instant retainUntil = AuditHashing.normalize(now.plus(retention));
        String key = String.format("anchors/%s/%020d.json", tenant, sequence);
        String signature = signer.sign(AnchorSigner.statement(tenant, sequence, headHash, now.toString()));
        var body = json.createObjectNode();
        body.put("version", "reconcile-audit-anchor/v1");
        body.put("tenantId", tenant);
        body.put("sequence", sequence);
        body.put("headHash", headHash);
        body.put("anchoredAt", now.toString());
        body.put("keyId", signer.keyId());
        body.put("signature", signature);
        String versionId = store.put(key, body.toString().getBytes(StandardCharsets.UTF_8), retainUntil);
        jdbc.update("""
            INSERT INTO audit_anchor (id, tenant_id, anchored_sequence, head_hash, object_key, object_version_id, key_id,
                                      signature, anchored_at, retain_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), tenant, sequence, headHash, key, versionId, signer.keyId(), signature,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(retainUntil));
        meters.counter("reconcile.audit.anchor", "result", "ok").increment();
        log.info("anchored audit chain of tenant {} at sequence {}", tenant, sequence);
        return true;
    }

    public List<AnchorView> list(String tenant) {
        return jdbc.query("SELECT * FROM audit_anchor WHERE tenant_id = ? ORDER BY anchored_sequence DESC LIMIT 200", VIEW, tenant);
    }

    /** Checks every anchor of the tenant against the immutable copy and against the current chain. */
    public AnchorVerification verify(String tenant) {
        List<AnchorView> anchors = jdbc.query("SELECT * FROM audit_anchor WHERE tenant_id = ? ORDER BY anchored_sequence", VIEW, tenant);
        int checked = 0;
        for (AnchorView a : anchors) {
            String problem = check(tenant, a);
            if (problem != null) {
                meters.counter("reconcile.audit.anchor.verify", "result", "mismatch").increment();
                return new AnchorVerification(false, checked, a.anchoredSequence(), problem);
            }
            checked++;
        }
        return new AnchorVerification(true, checked, null, null);
    }

    private String check(String tenant, AnchorView a) {
        JsonNode doc;
        try {
            doc = json.readTree(store.get(a.objectKey(), a.objectVersionId()));
        } catch (Exception e) {
            return "anchor object cannot be read from external storage (" + e.getClass().getSimpleName() + ")";
        }
        String statement = AnchorSigner.statement(doc.path("tenantId").asText(), doc.path("sequence").asLong(),
            doc.path("headHash").asText(), doc.path("anchoredAt").asText());
        if (!signer.verify(statement, doc.path("signature").asText())) {
            return "signature of the stored anchor is invalid";
        }
        if (!tenant.equals(doc.path("tenantId").asText()) || a.anchoredSequence() != doc.path("sequence").asLong()
            || !a.headHash().equals(doc.path("headHash").asText())) {
            return "database anchor row differs from the stored anchor";
        }
        List<String> chain = jdbc.queryForList("SELECT hash FROM audit_event WHERE tenant_id = ? AND sequence = ?",
            String.class, tenant, a.anchoredSequence());
        if (chain.isEmpty() || !chain.get(0).trim().equals(a.headHash())) {
            return "audit chain at sequence " + a.anchoredSequence() + " no longer matches the anchored hash (history rewritten or deleted)";
        }
        return null;
    }
}
