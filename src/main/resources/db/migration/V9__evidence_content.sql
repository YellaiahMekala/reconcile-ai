-- V9: keep the original uploaded file, not only its extracted text.
--
-- Until now an upload was hashed, parsed by Tika and chunked into vector_store, and the bytes were dropped. That left
-- no way to show an auditor the document the proposal cited, or to re-parse / re-embed after a model or splitter change.
--
-- The bytes live in their own table so loading an Evidence row (list calls, readiness counts) never drags a 10 MB blob
-- along. Same tenant guard as every other table: application filter plus Row-Level Security.
-- Rows uploaded before this migration have no content row; the download endpoint answers 404 CONTENT_NOT_AVAILABLE.

CREATE TABLE evidence_content (
    evidence_id  UUID        PRIMARY KEY REFERENCES evidence (id) ON DELETE CASCADE,
    tenant_id    VARCHAR(64) NOT NULL,
    content      BYTEA       NOT NULL
);

CREATE INDEX idx_evidence_content_tenant ON evidence_content (tenant_id);

ALTER TABLE evidence_content ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence_content
    USING (rls_tenant_allows(tenant_id))
    WITH CHECK (rls_tenant_allows(tenant_id));
