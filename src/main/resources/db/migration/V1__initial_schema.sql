-- Phase 1 (Tier 1) schema: break_item + evidence.
-- pgvector, vector_store, audit chain, approval tables are added in their own
-- migrations at Tier 2 (per TDD build order) — deliberately not here.

CREATE TABLE break_item (
    id               UUID PRIMARY KEY,
    tenant_id        VARCHAR(64)   NOT NULL,
    break_reference  VARCHAR(128)  NOT NULL,
    status           VARCHAR(32)   NOT NULL,
    ledger_amount    NUMERIC(19,4) NOT NULL,
    external_amount  NUMERIC(19,4) NOT NULL,
    currency_code    CHAR(3)       NOT NULL,
    source           VARCHAR(32)   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_break_tenant_reference UNIQUE (tenant_id, break_reference)
);

CREATE INDEX idx_break_item_tenant ON break_item (tenant_id);
CREATE INDEX idx_break_item_tenant_status ON break_item (tenant_id, status);

CREATE TABLE evidence (
    id             UUID PRIMARY KEY,
    break_item_id  UUID          NOT NULL,
    tenant_id      VARCHAR(64)   NOT NULL,
    file_name      VARCHAR(512)  NOT NULL,
    content_type   VARCHAR(128)  NOT NULL,
    content_hash   CHAR(64)      NOT NULL,
    size_bytes     BIGINT        NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_evidence_break FOREIGN KEY (break_item_id)
        REFERENCES break_item (id) ON DELETE CASCADE,
    CONSTRAINT uq_evidence_dedup UNIQUE (tenant_id, break_item_id, content_hash)
);

CREATE INDEX idx_evidence_break_tenant ON evidence (break_item_id, tenant_id);
