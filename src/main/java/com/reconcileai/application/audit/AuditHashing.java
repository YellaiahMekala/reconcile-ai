package com.reconcileai.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Canonical form + SHA-256 for audit events. Shared by the writer and the verifier so both hash identical bytes.
 *
 * <pre>
 *  hash = SHA-256( prevHash | tenant | sequence | eventType | aggregateType | aggregateId | actor | details | occurredAt )
 * </pre>
 * Canonicalisation rules (each one prevents a false "tampered" result):
 * <ul>
 *   <li>details: keys sorted, compact JSON, stored as TEXT (jsonb would normalise and change the bytes)</li>
 *   <li>occurredAt: truncated to microseconds, because Postgres TIMESTAMPTZ drops nanoseconds on write</li>
 *   <li>null aggregateId hashes as the empty string</li>
 * </ul>
 */
public final class AuditHashing {

    /** prev_hash of the first event in every tenant chain. */
    public static final String GENESIS = "0".repeat(64);

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private AuditHashing() {}

    public static Instant normalize(Instant t) {
        return t.truncatedTo(ChronoUnit.MICROS);
    }

    public static String canonicalDetails(Map<String, Object> details) {
        try {
            return CANONICAL.writeValueAsString(details == null ? Map.of() : new TreeMap<>(details));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serialisable", e);
        }
    }

    public static String hash(String prevHash, String tenantId, long sequence, String eventType, String aggregateType,
                              UUID aggregateId, String actor, String canonicalDetails, Instant occurredAt) {
        String material = String.join("|", prevHash, tenantId, Long.toString(sequence), eventType, aggregateType,
            aggregateId == null ? "" : aggregateId.toString(), actor, canonicalDetails, normalize(occurredAt).toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
