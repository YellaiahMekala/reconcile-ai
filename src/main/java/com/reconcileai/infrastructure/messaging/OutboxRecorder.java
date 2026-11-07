package com.reconcileai.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a domain event to {@code outbox_event} inside the caller's transaction (MANDATORY: calling it outside one is
 * a bug). Always on and cheap; whether anything is published is decided by {@link OutboxRelay}.
 * Payloads carry identifiers and amounts only, never evidence text or model output.
 */
@Component
public class OutboxRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxRecorder(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(String tenantId, String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, aggregateType, aggregateId, eventType, json.writeValueAsString(payload),
                Timestamp.from(Instant.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload not serialisable", e);
        }
        return id;
    }
}
