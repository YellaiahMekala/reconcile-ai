-- V11: supervised investigation mode (Phase 4G).
--
-- 1. investigation_job.mode gains SUPERVISED and SUPERVISED_PLANNED (18 characters), so the column is widened.
-- 2. investigation_job.review_verdict: AGREE | CHALLENGE | NOT_RUN from the reviewer agent (null outside supervised
--    mode). The verdict reaches routing only through the policy gate rule REVIEWER_CHALLENGE.
-- 3. investigation_job.risk_flags: the risk checker's advisory flags, newline-separated, shown to the approver.
--    Masked and length-capped before they are stored; they never change the route.
-- 4. agent_tool_call.agent_name: which specialist made each call (null for agent mode rows). Tenant guard and RLS
--    on agent_tool_call are unchanged (V10).

ALTER TABLE investigation_job ALTER COLUMN mode TYPE VARCHAR(24);
ALTER TABLE investigation_job ADD COLUMN review_verdict VARCHAR(16);
ALTER TABLE investigation_job ADD COLUMN risk_flags TEXT;

ALTER TABLE agent_tool_call ADD COLUMN agent_name VARCHAR(32);
