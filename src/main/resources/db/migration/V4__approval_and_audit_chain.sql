-- Phase 2C: maker-checker approval tasks and the tamper-evident, append-only audit chain.

CREATE TABLE approval_task (
    id                UUID PRIMARY KEY,
    tenant_id         VARCHAR(64)   NOT NULL,
    proposal_id       UUID          NOT NULL REFERENCES resolution_proposal (id),
    break_item_id     UUID          NOT NULL REFERENCES break_item (id) ON DELETE CASCADE,
    required_role     VARCHAR(32)   NOT NULL,              -- APPROVER_L1 | APPROVER_L2
    status            VARCHAR(16)   NOT NULL,              -- OPEN | APPROVED | REJECTED
    maker_subject     VARCHAR(128)  NOT NULL,              -- copied from the proposal; checker must differ
    decided_by        VARCHAR(128),
    decision_comment  VARCHAR(1000),
    decided_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_approval_task_proposal UNIQUE (proposal_id),
    -- Maker-checker is ALSO a database invariant: a decided task can never carry the maker as decider.
    CONSTRAINT ck_approval_maker_checker CHECK (decided_by IS NULL OR decided_by <> maker_subject)
);

CREATE INDEX idx_approval_queue ON approval_task (tenant_id, status, required_role, created_at);

-- Head of each tenant's chain. SELECT ... FOR UPDATE on this row serialises appends per tenant
-- (no gaps, no forks) without locking other tenants.
CREATE TABLE audit_chain_head (
    tenant_id      VARCHAR(64) PRIMARY KEY,
    last_sequence  BIGINT      NOT NULL,
    last_hash      CHAR(64)    NOT NULL
);

CREATE TABLE audit_event (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    sequence        BIGINT        NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    aggregate_type  VARCHAR(32)   NOT NULL,
    aggregate_id    UUID,
    actor           VARCHAR(128)  NOT NULL,
    details         TEXT          NOT NULL,   -- canonical JSON as TEXT (jsonb would re-order keys and break the hash)
    occurred_at     TIMESTAMPTZ   NOT NULL,
    prev_hash       CHAR(64)      NOT NULL,
    hash            CHAR(64)      NOT NULL,
    CONSTRAINT uq_audit_tenant_sequence UNIQUE (tenant_id, sequence)
);

CREATE INDEX idx_audit_aggregate ON audit_event (tenant_id, aggregate_id, sequence);

-- Append-only at the database level: the application role cannot rewrite history even with a bug.
CREATE OR REPLACE FUNCTION audit_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();
