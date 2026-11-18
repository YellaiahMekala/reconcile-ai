-- V15: EKA procedure knowledge consulted by a single-call investigation (platform Q1.4).
--
-- knowledge_outcome : OK, EMPTY, UNAVAILABLE or REJECTED; null when knowledge is off or the mode does not use it
-- knowledge_reason  : short code for UNAVAILABLE / REJECTED (TIMEOUT, AUTH, CIRCUIT_OPEN, ALL_HITS_SCREENED, ...)
-- knowledge_refs    : comma-separated EKA chunk refs ("kb:<uuid>") shown to the model as "procedure consulted".
--                     Kept apart from retrieved_chunk_ids on purpose: knowledge is never break evidence and can never
--                     satisfy the citation check. No passage text is stored.

ALTER TABLE investigation_job ADD COLUMN knowledge_outcome VARCHAR(16);
ALTER TABLE investigation_job ADD COLUMN knowledge_reason VARCHAR(48);
ALTER TABLE investigation_job ADD COLUMN knowledge_refs TEXT;
