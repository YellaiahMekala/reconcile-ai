# ADR-Q3: RERO integration (run token, internal read-only API, RERO mode)

- **Status:** accepted for the Q3 experiment. RERO is **off by default** (`RERO_ENABLED=false`). The keep or cut decision was
  made at Q3.7 under the frozen `RERO_EVALUATION_RULE.md`: **CUT** (2026-11-18, E-32). RERO stays off by default.
- **Scope:** reconcile-ai side of platform Q3.2 and Q3.5. Contract: `platform/contracts/rero/CONTRACT_C.md` (+ `*.v1.json`).

## Decision

1. **Run capability token (Q3.2).** reconcile-ai mints one ES256 JWS per RERO job (`iss=reconcile-ai`,
   `aud=reconcile-internal`, `sub=rero`, `tenant_id`, `job_id`, `scope=tools:read`, `jti`, `iat`, `exp`). TTL is
   deadline + 30 s. The P-256 key pair comes from PEM environment variables. The token lives only in memory for the start
   request. It is never logged or stored, and `StartRequest.toString()` leaves it out.
2. **Separate security chain for `/internal/**`**, ordered before the user chain:
   - Any method other than GET gets `405` before authentication.
   - Only a run token passes: the ES256 algorithm and our `kid` are pinned, and iss, aud, sub, scope, tenant, job, jti,
     exp and a 150 s maximum lifetime are checked. A user RS256 JWT fails with `401 RUN_TOKEN_INVALID`.
   - The user decoder rejects `aud=reconcile-internal`, as well as RS256 pinning, so a run token cannot reach a user API.
   - Without keys (the default build), every internal request gets `401`.
3. **Job-state checks per call.** These are server-side invalidation, not revocation:
   - The path job id must equal the token's `job_id` (`403 RUN_TOKEN_JOB_MISMATCH`).
   - The job must exist for the token's tenant. An unknown job, or another tenant's job, also gets `403`; it never gets
     `404`, which would confirm the id exists.
   - The job must be in mode RERO with status RUNNING, otherwise `409 JOB_NOT_RUNNING`.
4. **Atomic budget.** One SQL statement counts each call:
   `UPDATE investigation_job SET tool_calls_used = tool_calls_used + 1 WHERE ... AND tool_calls_used < tool_calls_max
   RETURNING ...`. When the budget is spent the call gets `429 TOOL_BUDGET_EXCEEDED`. The counter is not a writable JPA
   field, so saving the entity cannot overwrite it (migration V16).
5. **Tool outputs.**
   - Outputs are PII-masked with `PiiMasker`, and any text `InjectionScanner` flags is withheld.
   - Each call is audited by tool name, sequence and outcome only: `agent_tool_call` stores the empty-argument hash, and
     `audit_event` gets a `RERO_TOOL_CALL` entry.
   - Served evidence chunk ids go to `rero_served_chunk`.
6. **RERO mode (Q3.5).**
   - `InvestigationMode.RERO` is refused with `409 RERO_DISABLED` unless RERO is enabled.
   - The client uses `RestClient`: `POST` with `Idempotency-Key=jobId`, accepting 202 (new run) or 200 (same run), then
     polls until the run is terminal or until our deadline (deadline + grace).
   - The service token is the tenant's own client, through the existing `TenantServiceTokens`. Tenant matching is exact,
     and the token must now carry both `rero-api` and `ai-gateway` (TDD 2.2), otherwise nothing is sent.
7. **Citations are re-checked by reconcile-ai.** `citedChunkIds` must be a subset of the ids this job's evidence tool
   served, and `kb:` refs are never citable. A violation is the contract failure `RERO_INVALID_CITATION`, and no proposal
   is written. The draft then goes through the **same** citation verifier, policy gate and `ProposalService` path as
   every other mode.
8. **One-way self-check.** `CHALLENGE` becomes `reviewerChallenged=true`, which sends the break to manual review.
   `PASS` maps to `false`, which is the default, so it can never lower review. Auto-resolve stays off. Approval and
   maker-checker are unchanged. `ReroArchitectureTest` keeps all RERO classes away from approval, resolution and routing
   code.

## Choices and their reasons

- **Governance (`MODE_NOT_GOVERNED`).** reconcile-ai cannot see RERO's model calls. While reconcile-ai's gateway route is
  on, RERO counts as governed only if the operator declares `RERO_MODEL_VIA_GATEWAY=true` (meaning RERO runs
  `GatewayModel` with the forwarded two-audience token). Otherwise it is refused like the agent modes. This is a
  declaration, not a proof; a live check needs RERO's gateway invocation ids against the gateway ledger, which is Q3.7
  work.
- **All RERO failures are retryable.** The job fails with `RERO_<code>`, the break goes to FAILED_RETRYABLE, and no
  proposal is written (TDD 7).
  - `UNKNOWN_OUTCOME` does **not** set reconcile-ai's outcome-unknown re-run block. RERO's gateway keys are scoped to the
    job (`{jobId}:draft` and `{jobId}:selfcheck`), so a new job can never replay or double the old job's calls. The
    reported gateway invocation ids are kept on the job and in the audit entry for a ledger check.
  - `MODEL_BLOCKED` is also retryable, because RERO does not say whether the block was input sensitivity or a transient
    state.
- **Recovery.** An orphaned RUNNING RERO job is failed as `INTERRUPTED` without the unknown-outcome flag, for the same
  reason. As soon as the job is terminal, its run token stops working.
- **Similar breaks.** These are approved proposals whose break is RESOLVED, up to 5. `resolvedAt` is the break's last
  update.
- **Knowledge tool.** It uses EKA through the existing knowledge port when enabled; otherwise it returns `UNAVAILABLE`.
  Its procedures are never citable.

## Consequences

- Default build and CI need no external service. The RERO ITs use a fake RERO (`FakeReroServer`) that calls the real
  internal API with the run token. Contract schemas are copied to `src/test/resources/contracts/rero/` and checked from
  both sides.
- The new test tag `rero-live` is excluded by surefire and failsafe, for future tests against a real RERO.
- Open items:
  - live two-audience token proof against the platform realm;
  - the RERO gateway-route proof;
  - the Q3.7 evaluation.
