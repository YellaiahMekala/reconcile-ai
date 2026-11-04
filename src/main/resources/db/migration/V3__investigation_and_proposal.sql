-- Phase 2B: async investigation jobs, AI proposals, and per-call LLM token accounting.

CREATE TABLE investigation_job (
    id                      UUID PRIMARY KEY,
    tenant_id               VARCHAR(64)  NOT NULL,
    break_item_id           UUID         NOT NULL REFERENCES break_item (id) ON DELETE CASCADE,
    status                  VARCHAR(16)  NOT NULL,           -- QUEUED | RUNNING | SUCCEEDED | FAILED
    stage                   VARCHAR(32)  NOT NULL,           -- progress detail for polling / SSE
    initiated_by            VARCHAR(128) NOT NULL,           -- JWT sub of the analyst (the MAKER)
    analyst_note            VARCHAR(1000),
    retrieved_chunk_ids     TEXT,                            -- snapshot used for citation verification
    context_token_estimate  INTEGER,
    failure_reason          VARCHAR(256),
    proposal_id             UUID,
    owner_node              VARCHAR(64),                     -- which app instance claimed it
    heartbeat_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL,
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_job_tenant_break ON investigation_job (tenant_id, break_item_id);
CREATE INDEX idx_job_status_heartbeat ON investigation_job (status, heartbeat_at);

-- At most ONE active investigation per break, enforced by the database (not just an if-check),
-- so two concurrent POSTs can never spend tokens on the same break twice.
CREATE UNIQUE INDEX uq_job_one_active_per_break
    ON investigation_job (break_item_id) WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE resolution_proposal (
    id                   UUID PRIMARY KEY,
    tenant_id            VARCHAR(64)   NOT NULL,
    break_item_id        UUID          NOT NULL REFERENCES break_item (id) ON DELETE CASCADE,
    job_id               UUID          NOT NULL REFERENCES investigation_job (id),
    root_cause           VARCHAR(32)   NOT NULL,
    explanation          TEXT          NOT NULL,
    recommended_action   TEXT          NOT NULL,
    confidence           NUMERIC(4,3)  NOT NULL,
    cited_chunk_ids      TEXT          NOT NULL,
    citations_valid      BOOLEAN       NOT NULL,
    invalid_citations    TEXT,
    route                VARCHAR(32)   NOT NULL,             -- RoutingDecision
    route_reason         VARCHAR(64)   NOT NULL,
    status               VARCHAR(32)   NOT NULL,             -- ProposalStatus
    maker_subject        VARCHAR(128)  NOT NULL,
    variance_amount      NUMERIC(19,4) NOT NULL,             -- materiality snapshot at proposal time
    currency_code        VARCHAR(3)    NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_proposal_tenant_break ON resolution_proposal (tenant_id, break_item_id);

CREATE TABLE llm_call (
    id                 UUID PRIMARY KEY,
    tenant_id          VARCHAR(64)  NOT NULL,
    job_id             UUID,
    model              VARCHAR(128) NOT NULL,
    prompt_tokens      INTEGER      NOT NULL,
    completion_tokens  INTEGER      NOT NULL,
    total_tokens       INTEGER      NOT NULL,
    latency_ms         BIGINT       NOT NULL,
    outcome            VARCHAR(16)  NOT NULL,                -- OK | ERROR | BLOCKED
    created_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_llm_call_tenant_created ON llm_call (tenant_id, created_at);
