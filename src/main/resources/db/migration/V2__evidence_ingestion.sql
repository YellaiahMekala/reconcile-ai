-- Phase 2A: evidence ingestion outcome columns.
-- NOTE: the vector_store table is NOT created here. Spring AI's PgVectorStore creates it
-- (initialize-schema: true, decision A2). Flyway only owns relational tables.

ALTER TABLE evidence
    ADD COLUMN chunk_count         INTEGER,
    ADD COLUMN suspect_chunk_count INTEGER,
    ADD COLUMN failure_reason      VARCHAR(512);

-- Readiness guard counts evidence by status per break.
CREATE INDEX idx_evidence_break_status ON evidence (tenant_id, break_item_id, status);
