-- V7: daily operations digest, one row per tenant per UTC day.
-- Regenerating a day overwrites its row (upsert), so a re-run after a crash or a manual backfill is idempotent.

CREATE TABLE daily_digest (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    digest_date   DATE        NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL,
    summary       JSONB       NOT NULL,     -- every number in the digest, machine-readable
    narrative     TEXT        NOT NULL,     -- the same numbers as short plain text for humans
    CONSTRAINT uq_digest_tenant_date UNIQUE (tenant_id, digest_date)
);

ALTER TABLE daily_digest ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON daily_digest
    USING (rls_tenant_allows(tenant_id)) WITH CHECK (rls_tenant_allows(tenant_id));
