-- V14: what the gateway ledger said when an unknown outcome was reconciled.
--
-- Before V14 the reconcile step stored only a person's reference. When that reference is a gateway invocation id,
-- reconcile-ai now reads GET /api/v1/invocations/{id} itself and keeps the answer, so the audit shows whether the
-- call actually ran (EXECUTED), did not run (NOT_EXECUTED) or is still unknown (UNKNOWN) at the time of the decision.
-- Both columns stay null when the reference is a ticket id or the gateway route is off.
--
-- ledger_execution_certainty : the ledger's executionCertainty
-- ledger_status              : the ledger's status (OK, UNKNOWN_OUTCOME, BUDGET_BLOCKED, ...)

ALTER TABLE investigation_job ADD COLUMN ledger_execution_certainty VARCHAR(32);
ALTER TABLE investigation_job ADD COLUMN ledger_status VARCHAR(48);
