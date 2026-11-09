-- V10: investigation agent (Phase 4B).
--
-- 1. investigation_job.mode: SINGLE_CALL (the original one-prompt flow, still the default) or AGENT (the model picks
--    read-only tools). Existing rows are SINGLE_CALL.
-- 2. agent_tool_call: one row per tool call the agent made, so a reviewer can see what the model looked at.
--    Arguments are stored as a SHA-256 hash, never as text: an argument is model-written and may repeat evidence.
--    Same tenant guard as every other table: application filter plus Row-Level Security.

ALTER TABLE investigation_job ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE_CALL';

CREATE TABLE agent_tool_call (
    id            UUID        PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    job_id        UUID        NOT NULL REFERENCES investigation_job (id) ON DELETE CASCADE,
    seq           INTEGER     NOT NULL,
    tool          VARCHAR(64) NOT NULL,
    arg_hash      CHAR(64)    NOT NULL,
    result_chars  INTEGER     NOT NULL,
    outcome       VARCHAR(24) NOT NULL,          -- OK | DENIED | LIMIT_REACHED | INJECTION_FILTERED | ERROR
    latency_ms    BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, seq)
);

CREATE INDEX idx_agent_tool_call_tenant_job ON agent_tool_call (tenant_id, job_id);

ALTER TABLE agent_tool_call ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON agent_tool_call
    USING (rls_tenant_allows(tenant_id))
    WITH CHECK (rls_tenant_allows(tenant_id));
