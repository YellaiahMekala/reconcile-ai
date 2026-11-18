package com.reconcileai.infrastructure.persistence;

import com.reconcileai.infrastructure.security.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code idempotency_record} table (V17, platform D1.1.4). Every statement is tenant-scoped in its WHERE clause
 * and, on top, by Row-Level Security. Each call is a short autocommit statement: the claim INSERT commits at once, so
 * a concurrent duplicate sees it (or waits on it) through the primary key.
 */
@Component
public class IdempotencyStore {

    /** Who and what a key belongs to. {@code endpoint} is "METHOD path" with the concrete path. */
    public record Scope(String tenantId, String subject, String endpoint, String key) { }

    public enum State { IN_PROGRESS, COMPLETED }

    public record Stored(String requestHash, State state, int status, String contentType, String location,
                         byte[] body, Instant createdAt) { }

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tries to claim the key. Expired rows and abandoned IN_PROGRESS rows for the same scope are cleared first.
     *
     * @return true if this caller owns the key and must run the request
     */
    public boolean claim(Scope s, String requestHash, Duration ttl, Duration staleAfter) {
        Instant now = Instant.now();
        jdbc.update("DELETE FROM idempotency_record WHERE tenant_id = ? AND user_subject = ? AND endpoint = ? "
                + "AND idempotency_key = ? AND (expires_at < ? OR (state = 'IN_PROGRESS' AND created_at < ?))",
            s.tenantId(), s.subject(), s.endpoint(), s.key(), Timestamp.from(now), Timestamp.from(now.minus(staleAfter)));
        int inserted = jdbc.update("INSERT INTO idempotency_record (tenant_id, user_subject, endpoint, idempotency_key, "
                + "request_hash, state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?) "
                + "ON CONFLICT DO NOTHING",
            s.tenantId(), s.subject(), s.endpoint(), s.key(), requestHash, Timestamp.from(now),
            Timestamp.from(now.plus(ttl)));
        return inserted == 1;
    }

    public Optional<Stored> find(Scope s) {
        List<Stored> rows = jdbc.query("SELECT request_hash, state, response_status, response_content_type, "
                + "response_location, response_body, created_at FROM idempotency_record WHERE tenant_id = ? "
                + "AND user_subject = ? AND endpoint = ? AND idempotency_key = ?",
            (rs, i) -> new Stored(rs.getString(1).trim(), State.valueOf(rs.getString(2)), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getBytes(6), rs.getTimestamp(7).toInstant()),
            s.tenantId(), s.subject(), s.endpoint(), s.key());
        return rows.stream().findFirst();
    }

    public void complete(Scope s, int status, String contentType, String location, byte[] body) {
        jdbc.update("UPDATE idempotency_record SET state = 'COMPLETED', response_status = ?, response_content_type = ?, "
                + "response_location = ?, response_body = ? WHERE tenant_id = ? AND user_subject = ? AND endpoint = ? "
                + "AND idempotency_key = ? AND state = 'IN_PROGRESS'",
            status, contentType, location, body, s.tenantId(), s.subject(), s.endpoint(), s.key());
    }

    /** The first request failed (non-2xx or exception): forget the key so the client can retry with it. */
    public void release(Scope s) {
        jdbc.update("DELETE FROM idempotency_record WHERE tenant_id = ? AND user_subject = ? AND endpoint = ? "
                + "AND idempotency_key = ? AND state = 'IN_PROGRESS'",
            s.tenantId(), s.subject(), s.endpoint(), s.key());
    }

    /** Cross-tenant housekeeping (system scope): drops every expired key. @return rows deleted */
    public int purgeExpired() {
        return TenantScope.callAsSystem(() ->
            jdbc.update("DELETE FROM idempotency_record WHERE expires_at < ?", Timestamp.from(Instant.now())));
    }
}
