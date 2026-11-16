-- V13: our side of the ai-gateway ledger (platform Q2.4).
--
-- One row per HTTP attempt the gateway adapter made, so every terminal path can be reconciled as
--   job_id -> idempotency_key -> invocation_id
-- against the gateway's own ledger (GET /api/v1/invocations/{id}). A transport retry is a second row with the SAME
-- key and attempt = 2; the key never changes inside a job step. Prompts and answers are never stored here.
--
-- outcome: SUCCESS (200) | REFUSED (non-2xx, nothing ran) | UNKNOWN (may have run and been billed) | NOT_SENT
-- Same tenant guard as every other table: application filter plus Row-Level Security.

CREATE TABLE gateway_call (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    job_id           UUID         NOT NULL REFERENCES investigation_job (id) ON DELETE CASCADE,
    idempotency_key  VARCHAR(128) NOT NULL,
    attempt          INTEGER      NOT NULL,
    outcome          VARCHAR(16)  NOT NULL,
    http_status      INTEGER      NOT NULL,
    code             VARCHAR(48),
    invocation_id    UUID,
    gateway_status   VARCHAR(32),
    input_tokens     INTEGER      NOT NULL DEFAULT 0,
    output_tokens    INTEGER      NOT NULL DEFAULT 0,
    latency_ms       BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (job_id, idempotency_key, attempt)
);

CREATE INDEX idx_gateway_call_tenant_job ON gateway_call (tenant_id, job_id);
CREATE INDEX idx_gateway_call_invocation ON gateway_call (invocation_id) WHERE invocation_id IS NOT NULL;

ALTER TABLE gateway_call ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON gateway_call
    USING (rls_tenant_allows(tenant_id))
    WITH CHECK (rls_tenant_allows(tenant_id));
