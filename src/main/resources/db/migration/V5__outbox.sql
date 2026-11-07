-- Transactional outbox: domain events are written in the SAME transaction as the state change, then relayed to Kafka.
-- Delivery is at-least-once; consumers deduplicate on event id (sent as the "event-id" header).
CREATE TABLE outbox_event (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(32)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0
);

-- The relay only ever scans the unpublished tail.
CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;
