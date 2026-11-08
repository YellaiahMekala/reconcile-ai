-- V8: external anchors of each tenant's audit chain head.
-- A row records "at <anchored_at>, the chain of <tenant> ended at <anchored_sequence> with hash <head_hash>" and where the
-- signed copy lives in WORM storage (S3 Object Lock). The row is append-only here, and the object is immutable there.

CREATE TABLE audit_anchor (
    id                 UUID PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    anchored_sequence  BIGINT       NOT NULL,
    head_hash          CHAR(64)     NOT NULL,
    object_key         VARCHAR(400) NOT NULL,
    object_version_id  VARCHAR(200),
    key_id             VARCHAR(64)  NOT NULL,       -- which signing key produced the signature (rotation)
    signature          TEXT         NOT NULL,       -- Ed25519, base64
    anchored_at        TIMESTAMPTZ  NOT NULL,
    retain_until       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_audit_anchor_tenant_seq UNIQUE (tenant_id, anchored_sequence)
);

CREATE OR REPLACE FUNCTION audit_anchor_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_anchor is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_anchor_append_only
    BEFORE UPDATE OR DELETE ON audit_anchor
    FOR EACH ROW EXECUTE FUNCTION audit_anchor_append_only();

ALTER TABLE audit_anchor ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_anchor
    USING (rls_tenant_allows(tenant_id)) WITH CHECK (rls_tenant_allows(tenant_id));
