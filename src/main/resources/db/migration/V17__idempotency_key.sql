-- V17: Idempotency-Key for state-changing POST endpoints (platform D1.1.4).
--
-- One row per (tenant, user, endpoint, key). The primary key is the serialisation point: two concurrent requests with
-- the same key race on INSERT ... ON CONFLICT DO NOTHING, exactly one wins and runs, the other waits for the stored
-- response and replays it.
--
-- request_hash : SHA-256 of the canonical request (method, path, canonical JSON or multipart parts). Same key with a
--                different hash is refused with 422 IDEMPOTENCY_KEY_REUSED.
-- state        : IN_PROGRESS while the first request runs; COMPLETED once its 2xx response is stored. Non-2xx
--                responses are not stored (the row is deleted) so the client can retry with the same key.
-- response_*   : status, content type, Location and body of the original 2xx response, replayed byte for byte.
-- expires_at   : created_at + TTL (24h default); purged by IdempotencyPurgeJob.
-- Same tenant guard as every other table: application filter plus Row-Level Security.

CREATE TABLE idempotency_record (
    tenant_id              VARCHAR(64)  NOT NULL,
    user_subject           VARCHAR(255) NOT NULL,
    endpoint               VARCHAR(256) NOT NULL,
    idempotency_key        VARCHAR(128) NOT NULL,
    request_hash           CHAR(64)     NOT NULL,
    state                  VARCHAR(16)  NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status        INTEGER,
    response_content_type  VARCHAR(128),
    response_location      TEXT,
    response_body          BYTEA,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, user_subject, endpoint, idempotency_key)
);

CREATE INDEX idx_idempotency_record_expires ON idempotency_record (expires_at);

ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_record
    USING (rls_tenant_allows(tenant_id))
    WITH CHECK (rls_tenant_allows(tenant_id));
