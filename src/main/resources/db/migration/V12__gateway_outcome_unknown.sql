-- V12: ai-gateway outcome-unknown guard (platform Q2.3).
--
-- A job that failed after its gateway call may have run (UNKNOWN_OUTCOME, IDEMPOTENCY_UNKNOWN_OUTCOME, a transport
-- failure after the request was sent, or a node that died mid-call) may already have been billed. Re-running the break
-- starts a new job with a new idempotency key, which would bill again. So the break cannot be re-investigated until an
-- approver has checked the gateway ledger and recorded a reference.
--
-- outcome_unknown : set when the job fails with an unknown gateway outcome
-- reconciled_at/by: who cleared it and when; both null until cleared
-- reconcile_ref   : the gateway invocation id or a ticket id (an identifier, never free text)

ALTER TABLE investigation_job ADD COLUMN outcome_unknown BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE investigation_job ADD COLUMN reconciled_at TIMESTAMPTZ;
ALTER TABLE investigation_job ADD COLUMN reconciled_by VARCHAR(128);
ALTER TABLE investigation_job ADD COLUMN reconcile_ref VARCHAR(64);

-- The re-run gate looks up unreconciled jobs per tenant and break.
CREATE INDEX idx_investigation_job_unreconciled
    ON investigation_job (tenant_id, break_item_id)
    WHERE outcome_unknown AND reconciled_at IS NULL;
