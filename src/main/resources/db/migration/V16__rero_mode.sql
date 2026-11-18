-- V16: RERO investigation mode (platform Q3.2 / Q3.5). Unused unless reconcile.rero.enabled=true.
--
-- 1. investigation_job.tool_calls_max / tool_calls_used: the run's tool budget for the internal read-only API.
--    The counter is only ever changed by one conditional UPDATE (used < max), so concurrent RERO tool calls can never
--    exceed the budget. It is not mapped as a writable JPA field, so saving the job entity never overwrites it.
-- 2. investigation_job.rero_run_id / rero_gateway_invocation_ids: correlation with RERO's run and the gateway ledger.
-- 3. rero_served_chunk: every evidence chunk id the internal evidence tool served to a job. The ONLY ids a RERO
--    result may cite (reconcile-ai re-checks citations against this, whatever RERO says). Same tenant guard and RLS.

ALTER TABLE investigation_job ADD COLUMN tool_calls_max INTEGER;
ALTER TABLE investigation_job ADD COLUMN tool_calls_used INTEGER NOT NULL DEFAULT 0;
ALTER TABLE investigation_job ADD COLUMN rero_run_id UUID;
ALTER TABLE investigation_job ADD COLUMN rero_gateway_invocation_ids TEXT;
ALTER TABLE investigation_job ADD CONSTRAINT chk_tool_calls_budget
    CHECK (tool_calls_used >= 0 AND (tool_calls_max IS NULL OR tool_calls_used <= tool_calls_max));

CREATE TABLE rero_served_chunk (
    tenant_id   VARCHAR(64) NOT NULL,
    job_id      UUID        NOT NULL REFERENCES investigation_job (id) ON DELETE CASCADE,
    chunk_id    VARCHAR(64) NOT NULL,
    served_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, chunk_id)
);

CREATE INDEX idx_rero_served_chunk_tenant_job ON rero_served_chunk (tenant_id, job_id);

ALTER TABLE rero_served_chunk ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rero_served_chunk
    USING (rls_tenant_allows(tenant_id))
    WITH CHECK (rls_tenant_allows(tenant_id));
