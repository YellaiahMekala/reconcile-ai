# PLATFORM_FACTS

Facts read from code on 2026-11-13 (commit 9ee707d), refreshed on 2026-11-14 for commit 0393997 (supervisor changes in #7 and #8) and on 2026-11-16 for the Q2 gateway work (5A virtual threads, Q2.1 to Q2.7 on branch `feature/q2-gateway-governance`, uncommitted at the time of writing; the last commit on main is `7c0f9c3`, Q2.3). Paths are relative to `reconcile-ai/`.
Abbreviations: `api/` = `src/main/java/com/reconcileai/api/`, `app/` = `.../application/`, `infra/` = `.../infrastructure/`,
`dom/` = `.../domain/`, `yml` = `src/main/resources/application.yml`. UNVERIFIED = not confirmed in code.

## 1. Endpoints

Roles: ANY = RECON_ANALYST, APPROVER_L1, APPROVER_L2, SRE_ADMIN. All paths need a JWT unless marked public (`infra/security/SecurityConfig.java`).

| Method | Path | Roles | Request | Success | Source |
|---|---|---|---|---|---|
| POST | /api/v1/breaks | RECON_ANALYST | JSON `breakReference` (string, required, not blank; DB max 128), `ledgerAmount` (string, required, regex `-?\d+(\.\d+)?`), `externalAmount` (same), `currency` (string, required, `[A-Z]{3}`) | 201, `Location` header, BreakResponse | `api/BreakController.java`, `api/dto/CreateBreakRequest.java`, `V1__initial_schema.sql` |
| GET | /api/v1/breaks?page=0&size=20 | ANY | query `page`, `size` | 200, plain JSON array of BreakResponse | `api/BreakController.java` |
| GET | /api/v1/breaks/{id} | ANY | path UUID | 200 BreakResponse | same |
| POST | /api/v1/breaks/{breakId}/evidence | RECON_ANALYST | multipart field `file`; max file 10MB, request 12MB | 201 new, 200 dedup hit (same SHA-256 within tenant+break), 400 empty file; EvidenceResponse | `api/EvidenceController.java`, `yml` multipart |
| GET | /api/v1/breaks/{breakId}/evidence | ANY | – | 200 array EvidenceResponse | `api/EvidenceController.java` |
| GET | /api/v1/breaks/{breakId}/evidence/{evidenceId}/content | ANY | – | 200 octet-stream, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff` | same |
| GET | /api/v1/breaks/{breakId}/evidence/search?q= | RECON_ANALYST, APPROVER_L1, APPROVER_L2 (not SRE_ADMIN) | `q` not blank, max 500 | 200 array SearchHit | `api/EvidenceSearchController.java` |
| POST | /api/v1/breaks/{breakId}/investigations | RECON_ANALYST | optional JSON `analystNote` (max 1000), `mode` (max 16: SINGLE_CALL default, AGENT, SUPERVISED, SUPERVISED_PLANNED) | **202**, `Location: /api/v1/investigations/{id}`, InvestigationJobResponse | `api/InvestigationController.java`, `app/investigation/InvestigationService.java` |
| GET | /api/v1/investigations/{jobId} | ANY | – | 200 InvestigationJobResponse (polling path) | `api/InvestigationController.java` |
| GET | /api/v1/investigations/{jobId}/events | ANY | `Authorization` header (use fetch, not EventSource) | `text/event-stream`, event name `progress`, JSON data; keep-alive comments; DB snapshot on subscribe; stream expires after 120 s | `api/InvestigationController.java`, `infra/streaming/SseProgressBroadcaster.java`, `yml` emitter-timeout-ms |
| GET | /api/v1/investigations/{jobId}/tool-calls | ANY | – | 200 array `{seq, tool, outcome, resultChars, latencyMs, agentName}`; no arguments | `infra/ai/agent/AgentToolCallStore.java` |
| POST | /api/v1/investigations/{jobId}/reconcile | SRE_ADMIN, APPROVER_L2 | JSON `reference` (required, `[A-Za-z0-9._:-]{3,64}`: a gateway invocation id or ticket id) | 200 InvestigationJobResponse; 409 `RECONCILER_MUST_DIFFER` (same `sub` as the starter), 409 `NOT_AWAITING_RECONCILIATION`, 409 `INVOCATION_NOT_FOR_JOB` (UUID reference not returned to this job's gateway calls; no ledger read), 409 `LEDGER_ENTRY_NOT_FOUND` (ledger 404), 503 `GATEWAY_LEDGER_UNAVAILABLE` + `Retry-After: 30` (ledger unreachable or non-200; nothing recorded), 404 other tenant. A canonical UUID reference with the gateway route on makes the app read `GET /api/v1/invocations/{id}` with the tenant's token before recording; a ticket reference skips the read. An `EXECUTED` ledger answer does not block reconciling. Audit `INVESTIGATION_OUTCOME_RECONCILED` records `ledgerChecked` plus certainty, status, tokens, cost | `api/InvestigationController.java`, `app/investigation/InvestigationService.java`, `V12__gateway_outcome_unknown.sql` (Q2.3), `V14` (ledger fields); `GatewayReconcileLedgerIT` |
| GET | /api/v1/investigations/{jobId}/gateway-calls | SRE_ADMIN, APPROVER_L2 | – | 200 array: each `gateway_call` row plus `ledgerResult` (`FOUND`, `NOT_FOUND`, `UNAVAILABLE`, `NOT_CHECKED`) and the ledger fields; 404 for another tenant's job | `api/InvestigationController.java`; `GatewayReconcileLedgerIT` |
| GET | /api/v1/breaks/{breakId}/proposals | ANY | – | 200 array ProposalResponse | `api/InvestigationController.java` |
| GET | /api/v1/proposals/{proposalId} | ANY | – | 200 ProposalResponse | same |
| GET | /api/v1/approvals | APPROVER_L1, APPROVER_L2 | – | 200 caller's queue; maker's own tasks filtered out | `api/ApprovalController.java`, `app/approval/ApprovalService.java:94` |
| GET | /api/v1/approvals/{taskId} | ANY | – | 200 ApprovalTaskResponse | `api/ApprovalController.java` |
| POST | /api/v1/approvals/{taskId}/decision | APPROVER_L1, APPROVER_L2 | JSON `decision` (required, APPROVE or REJECT), `comment` (max 1000). No approver/tenant/tier fields | 200 ApprovalTaskResponse | same |
| GET | /api/v1/audit/aggregates/{aggregateId} | ANY | – | 200 array `{sequence, eventType, aggregateType, aggregateId, actor, details, occurredAt, prevHash, hash}` | `api/AuditController.java`, `app/audit/AuditQueryService.java` |
| GET | /api/v1/audit/verify | SRE_ADMIN | – | 200 `{valid, eventsChecked, firstBrokenSequence, headHash}` (current tenant) | same |
| GET | /api/v1/audit/anchors, /api/v1/audit/anchors/verify | SRE_ADMIN | – | 200 | `api/AuditAnchorController.java` |
| GET | /api/v1/digests?days=7 | APPROVER_L1, APPROVER_L2, SRE_ADMIN | `days` clamped 1..90 | 200 array `{tenantId, date, generatedAt, summary (map), narrative}` | `api/DigestController.java`, `app/digest/DailyDigestService.java` |
| GET | /api/v1/digests/{day} | same | ISO date | 200 Digest, 404 DIGEST_NOT_FOUND | same |
| POST | /api/v1/digests/{day}/generate | SRE_ADMIN | day not in future | 200 Digest | same |
| GET | /api/v1/auth/config | public | – | `{mode: demo|oidc, issuer, clientId}` | `api/AuthConfigController.java` |
| GET | /dev/personas, POST /dev/token | public, exist only under profile `demo-login` | – | demo token, 3600 s | `infra/security/DemoLoginController.java` |
| GET | /actuator/health, /actuator/prometheus | public | – | – | `SecurityConfig.java`, `yml` exposure `health,prometheus` |

**Response DTOs** (`api/dto/`):
- BreakResponse: `id, breakReference, status, ledgerAmount, externalAmount, variance, currency, source, version, createdAt`. Amounts are `BigDecimal`; no `@JsonFormat(STRING)` or `WRITE_BIGDECIMAL_AS_STRING` found, so they serialize as **JSON numbers**. Only requests and Kafka messages carry amounts as strings.
- EvidenceResponse: `id, breakItemId, fileName, contentType, contentHash, sizeBytes, status (PROCESSING|READY|FAILED), deduplicated, chunkCount, suspectChunkCount, failureReason, createdAt`.
- SearchHit: `chunkId, evidenceId, fileName, chunkIndex, vectorScore, keywordRank, fusedScore, snippet (max 300 chars)`.
- InvestigationJobResponse: `id, breakItemId, mode, status, stage, initiatedBy, contextTokenEstimate, failureReason, proposalId, createdAt, startedAt, finishedAt, reviewVerdict, riskFlags, outcomeUnknown, reconciledAt, reconciledBy, reconcileRef, ledgerExecutionCertainty, ledgerStatus`.
- ProposalResponse: `id, breakItemId, jobId, rootCause, explanation, recommendedAction, confidence (BigDecimal), citedChunkIds, citationsValid, invalidCitations, route, routeReason, status, makerSubject, varianceAmount, currency, createdAt`.
- ApprovalTaskResponse: `id, proposalId, breakItemId, requiredRole, status (OPEN|APPROVED|REJECTED), makerSubject, decidedBy, decisionComment, decidedAt, createdAt`.

**Model output contract:** `ProposalDraft(rootCause, explanation, recommendedAction, confidence, citedChunkIds)` (`infra/ai/prompt/ProposalDraft.java`). Stored as `ResolutionProposal`. Categories (`dom/RootCauseCategory.java`): TIMING_DIFFERENCE, FX_RATE_DIFFERENCE, FEE_OR_CHARGE, DUPLICATE_ENTRY, MISSING_ENTRY, DATA_ENTRY_ERROR, PARTIAL_SETTLEMENT, UNKNOWN. Chunk id = `UUID.nameUUIDFromBytes(evidenceId + ":" + chunkIndex)` (`infra/ai/ingestion/EvidenceIngestor.java`).

**Async and states:**
- Job status QUEUED, RUNNING, SUCCEEDED, FAILED (`dom/JobStatus.java`). Stage QUEUED, RETRIEVING, CALLING_MODEL, VERIFYING, ROUTING, COMPLETED, FAILED (`dom/JobStage.java`).
- Break status (`dom/BreakStatus.java`): OPEN, EVIDENCE_PROCESSING, READY_TO_INVESTIGATE, INVESTIGATING, PROPOSAL_CREATED, PENDING_L1, PENDING_L2, AUTO_RESOLVED, RESOLVED, REJECTED, MANUAL_REVIEW_REQUIRED, FAILED_RETRYABLE.
- Proposal status: PENDING_APPROVAL, MANUAL_REVIEW, APPROVED, REJECTED, AUTO_RESOLVED (`dom/ProposalStatus.java`).
- Start preconditions (`InvestigationService.start`): mode enabled (else 400 INPUT_REJECTED); per-tenant rate limit (429); note passes injection scan; no QUEUED/RUNNING job for the break (409 INVESTIGATION_IN_PROGRESS); the break's last job is not an unreconciled gateway unknown outcome (409 OUTCOME_UNRECONCILED, Q2.3); at least one READY evidence and none PROCESSING (409 EVIDENCE_NOT_READY); lifecycle allows it (409 ILLEGAL_BREAK_STATE). Executor queue full gives 503 + `Retry-After: 30`.
- Gateway route (`GATEWAY_ENABLED=true`, `infra/gateway/`, `app/gateway/`): the SINGLE_CALL model call goes to ai-gateway `POST /api/v1/chat` with a client-credentials token (`scope=aud-ai-gateway`) and key `recon:<jobId>:investigate:1`; one transport retry only on not-sent codes. Each attempt is a `gateway_call` row (V13: outcome SUCCESS / REFUSED / UNKNOWN / NOT_SENT, http status, code, invocation id, tokens); a success also writes `llm_call` with model `ai-gateway`. Failure mapping (`app/gateway/GatewayFailure.java`): reason `GATEWAY_<code>`; `UNKNOWN_OUTCOME`, `IDEMPOTENCY_UNKNOWN_OUTCOME`, `TRANSPORT_UNKNOWN_OUTCOME`, `IDEMPOTENCY_IN_PROGRESS` flag the job `outcome_unknown`; `RISK_BLOCKED`, `SENSITIVITY_BLOCKED` move the break to MANUAL_REVIEW_REQUIRED (`dom/BreakItem.markPolicyBlocked`); everything else leaves it FAILED_RETRYABLE. System and user text are PII-masked before the request leaves (`infra/ai/InvestigationAiClient.viaGateway`). The advisor chain and the Resilience4j breaker are not on this route.
- Evidence upload is refused (409) while the break is INVESTIGATING, PENDING_L1, PENDING_L2, RESOLVED, AUTO_RESOLVED (`dom/BreakItem.java:121`).
- Idempotency: break create is unique on (tenant_id, break_reference), duplicate gives 409. Evidence is deduplicated by SHA-256. No `Idempotency-Key` header support found in code.

**Errors** (`api/GlobalExceptionHandler.java`, `api/dto/ApiError.java`). Body is `{code, message, timestamp}` with `application/json`. **Not** problem+json (RFC 7807).

| HTTP | code |
|---|---|
| 400 | VALIDATION_FAILED, MALFORMED_REQUEST, INPUT_REJECTED, BAD_REQUEST |
| 403 | INSUFFICIENT_APPROVAL_TIER, MAKER_CHECKER_VIOLATION (and plain Spring 403 when a role is missing) |
| 404 | BREAK_NOT_FOUND, DIGEST_NOT_FOUND, other NotFoundException codes |
| 409 | DUPLICATE_BREAK_REFERENCE, CONFLICT, CONCURRENT_MODIFICATION, ILLEGAL_BREAK_STATE, APPROVAL_ALREADY_DECIDED, INVESTIGATION_IN_PROGRESS, EVIDENCE_NOT_READY, OUTCOME_UNRECONCILED, RECONCILER_MUST_DIFFER, NOT_AWAITING_RECONCILIATION, INVOCATION_NOT_FOR_JOB, LEDGER_ENTRY_NOT_FOUND |
| 429 | RATE_LIMITED + `Retry-After` |
| 503 | CAPACITY_EXCEEDED + `Retry-After: 30`; GATEWAY_LEDGER_UNAVAILABLE + `Retry-After: 30` (reconcile) |
| 401 | missing or invalid JWT, or JWT without `tenant_id` (Spring default body, not ApiError) |

**Policy gate** (`app/policy/PolicyGateService.java`). Input `PolicyInput(variance: Money, confidence, rootCause, citationsValid, reviewerChallenged)`. First match wins:
1. reviewerChallenged → MANUAL_REVIEW `REVIEWER_CHALLENGE`
2. citations invalid → MANUAL_REVIEW `CITATIONS_INVALID`
3. rootCause UNKNOWN → MANUAL_REVIEW `UNKNOWN_ROOT_CAUSE`
4. confidence < 0.80 → MANUAL_REVIEW `LOW_CONFIDENCE`
5. currency ≠ USD → L2_APPROVAL `NON_POLICY_CURRENCY`
6. variance ≥ 25000 → L2_APPROVAL `MATERIAL_VARIANCE`
7. auto-resolve enabled, variance ≤ 1000, confidence ≥ 0.95 → AUTO_RESOLVE `WITHIN_AUTO_POLICY`
8. otherwise → L1_APPROVAL `STANDARD`

Variance = |ledger − external|. `ProposalService.persistProposal` rewrites AUTO_RESOLVE to L1_APPROVAL in all cases (`app/investigation/ProposalService.java`). On decision, `ApprovalService` requires L2 if the task is L2 **or** `requiresL2(variance, currency)` recomputes true. Maker ≠ checker is enforced (`app/approval/ApprovalService.java:119-135`).

## 2. Auth

Source: `infra/security/SecurityConfig.java`, `TenantContext.java`, `yml`, `docker/keycloak/reconcile-realm.json`.
- Algorithm RS256, pinned in local-key mode.
- **Local mode** (`OIDC_ISSUER_URI` empty, the default): public key PEM from `JWT_PUBLIC_KEY` (default `classpath:jwt/dev-public.pem`). Checks: signature, exp/nbf (60 s skew), non-blank `tenant_id`. **No issuer and no audience check.**
- **OIDC mode** (`OIDC_ISSUER_URI` set): JWKS discovered from the issuer (`JwtDecoders.fromIssuerLocation`). Checks issuer, `tenant_id`, and audience contains `OIDC_AUDIENCE` (default `reconcile-api`; blank skips the check).
- Claims: tenant `tenant_id`, user `sub` (principal), roles `roles` (string list; values RECON_ANALYST, APPROVER_L1, APPROVER_L2, SRE_ADMIN; unknown values ignored).
- Token lifetime: demo token 3600 s (`DemoLoginController.java:76`); Keycloak realm `accessTokenLifespan` 1800 s; scripted tokens from `scripts/generate-local-jwt.ps1` UNVERIFIED.
- Keycloak realm `reconcile`: one public client `reconcile-ui`, mappers for `tenant_id`, `roles`, audience `reconcile-api`. No confidential or service-account client.
- **Service-to-service today:** inbound, no dedicated mechanism. A caller needs a bearer JWT with `tenant_id`, `sub`, `roles`, signed by the configured key or issued by the configured IdP. Kafka intake has no per-message auth; the topic ACL is the boundary (README §8; no ACL config in repo). Outbound (Q2.1): `infra/gateway/ClientCredentialsTokenSource.java` gets a service token for ai-gateway with the client-credentials grant (`GATEWAY_TOKEN_URI`, `GATEWAY_CLIENT_ID`, `GATEWAY_CLIENT_SECRET`, `GATEWAY_TOKEN_SCOPE=aud-ai-gateway`), cached until 30 s before expiry. With `reconcile.gateway.tenants` empty (default) there is one client for the whole instance: the tenant the gateway bills is the one in that token, not the job's tenant. With the map set (bracket keys, for example `"[TENANT_A]": { client-id, client-secret-env }`, secret read from the named environment variable, never YAML) each job uses its tenant's client, the token's `tenant_id` claim must equal the job tenant exactly (`TOKEN_TENANT_MISMATCH`, or `TOKEN_TENANT_UNVERIFIED` when no claim is readable; signature not verified locally, the gateway verifies it), and a tenant with no entry fails with `TENANT_CREDENTIALS_MISSING` with no fallback to a shared client. These fail before sending: job reason `GATEWAY_<code>`, break FAILED_RETRYABLE, `gateway_call` outcome NOT_SENT, http 0. Startup fails fast on an incomplete entry, an unset secret variable, or two keys that collide after ignoring case and separators. Tested against a fake gateway and realm only; a real two-tenant run needs a second Keycloak client.
- **A new internal caller would need:** a client-credentials client in the IdP with mappers that put `tenant_id`, `roles` (for example RECON_ANALYST) and audience `reconcile-api` into the token. Note: maker-checker uses `sub`, so a service subject would be the maker of any proposal it starts. Not built.

## 3. Tenant identifier

- Taken only from the JWT claim `tenant_id` (`TenantContext.java`). Never from a header, param, body or tool argument. Kafka intake is the exception: the tenant comes from the message `tenantId`, and `__system__` is rejected (`infra/messaging/BreakIntakeListener.java:43`).
- Column `tenant_id VARCHAR(64) NOT NULL` on every tenant table (migrations V1–V11). No allowed-character pattern is enforced in code or DDL. Max length 64 comes from DDL only.
- Repositories extend `Repository`, and every finder takes `tenantId` (`infra/persistence/*Repository.java`).
- Postgres Row-Level Security: every connection sets `app.tenant_id`; unset means no rows; `__system__` is used for relay, sweeper and digest (`V6__row_level_security.sql`, `infra/security/TenantScope.java`).
- Cross-tenant read returns **404** (repository miss → NotFound). Maker-checker and tier refusals on a visible task return 403.

## 4. Integration-relevant config (`yml`)

| Name (env) | Default | Effect |
|---|---|---|
| OIDC_ISSUER_URI | empty | empty = local public-key mode; set = OIDC + JWKS + issuer check |
| OIDC_AUDIENCE | reconcile-api | required `aud` in OIDC mode |
| JWT_PUBLIC_KEY | classpath:jwt/dev-public.pem | local verification key |
| spring profile | mock-llm | chat model: mock-llm, ollama, openai, bedrock, bedrock-fake (`src/main/resources/application-*.yml`) |
| OPENAI_API_KEY | empty | embeddings always OpenAI text-embedding-3-small |
| AGENT_ENABLED | false | allows mode AGENT |
| SUPERVISED_ENABLED | false | allows SUPERVISED / SUPERVISED_PLANNED (also needs agent enabled); one shared budget of 12 tool calls, 24 model turns, 180 s, 6 delegations (`yml`, `infra/ai/agent/SupervisedProperties.java`) |
| RATE_LIMIT_ENABLED | true | 20 burst, 20/min per tenant on POST investigations |
| RATE_LIMIT_STORE (`reconcile.ratelimit.store`) | memory | `memory` = bucket per JVM; `redis` = one bucket per tenant shared by all replicas (atomic Lua script, Redis server clock, key `reconcile:ratelimit:investigations:{tenant}`, expires when the bucket would be full); Redis unreachable = local bucket, counted in `reconcile.ratelimit.redis.fallback` |
| reconcile.gateway.tenants | empty map | per-tenant gateway clients (`client-id`, `client-secret-env`); empty = shared client |
| GATEWAY_ENABLED | false | routes the SINGLE_CALL model call through ai-gateway (`infra/gateway/GatewayConfig.java`) |
| GATEWAY_BASE_URL / GATEWAY_TOKEN_URI | http://localhost:8080 / http://localhost:8180/realms/ai-gateway/protocol/openid-connect/token | gateway and token endpoint; the platform spike stack uses 8280 and `http://localhost:8190/realms/platform/...` |
| GATEWAY_CLIENT_ID / GATEWAY_CLIENT_SECRET / GATEWAY_TOKEN_SCOPE | reconcile-ai / empty / aud-ai-gateway | client-credentials grant; the realm adds `aud=ai-gateway` only for that scope |
| reconcile.gateway.connect-timeout / request-timeout | 3 s / 60 s | per attempt; a timeout after sending is an unknown outcome, never retried |
| spring.threads.virtual.enabled | true | Java 21 virtual threads for requests, `@Scheduled` and the default executor; the investigation executor stays a bounded platform pool (5A, `docs/adr/ADR-virtual-threads.md`) |
| KAFKA_ENABLED / KAFKA_BOOTSTRAP | false / localhost:9092 | creates producer, intake consumer and outbox relay |
| CACHE_ENABLED | false | Redis semantic cache of verified drafts (threshold 0.95, TTL 24 h, 500 per tenant) |
| SSE_FANOUT | local | `redis` = cross-instance SSE |
| PII_NER_ENABLED / PII_NER_FAIL_CLOSED | false / false | Presidio sidecar for names and locations |
| TRACING_ENABLED / OTLP_TRACES_ENDPOINT | false / http://localhost:4318/v1/traces | OTel traces |
| AUDIT_ANCHOR_ENABLED | false | signed chain heads to WORM storage every 15 min |
| DIGEST_ENABLED | true | daily digest at 00:15 UTC |
| SWAGGER_ENABLED | false | springdoc UI and spec, public when on |
| reconcile.policy.* | 0.80 / 25000 / USD / auto off, cap 1000, min 0.95 | gate thresholds (not env-driven) |
| reconcile.investigation.* | pool 2–4, queue 50, lease 300 s, context 3000 tokens, output 700 | job execution |
| reconcile.ai.resilience.* | 3 attempts, 500 ms backoff, 50% over 10 calls, open 30 s | `infra/ai/resilience/LlmResilience.java` |
| profile `openai` HTTP client | `spring.http.client.factory: simple`, connect 10 s, read 60 s | HTTP/1.1 for Spring AI's RestClient (`application-openai.yml`) |

## 5. Events (`infra/messaging/`)

- **Outbound** `reconcile.break.events` from `OutboxRelay` (outbox table, `FOR UPDATE SKIP LOCKED`, batch 50 every 1 s). Key = aggregate id (break id; digest uses name-UUID of tenant|day). Headers `event-id`, `event-type`, `tenant-id`. Value = JSON of the payload map only:
  - BREAK_CREATED `{breakReference, variance (string), currency}` (`app/BreakService.java:64`, `app/BreakIngestionService.java:56`)
  - PROPOSAL_ROUTED `{proposalId, route, reason}` (`app/investigation/ProposalService.java:76`)
  - BREAK_RESOLVED `{proposalId, tier}` (`app/approval/ResolutionCommitService.java:66`)
  - DAILY_DIGEST_READY `{digestDate, openTotal, resolved}` (`app/digest/DailyDigestService.java:134`)
- **Inbound** `reconcile.break.intake`, group `reconcile-ai-intake`. Payload `BreakIntakeMessage{tenantId, breakReference, ledgerAmount (string), externalAmount (string), currency}`. Creates a break with source KAFKA. 3 retries at 500 ms, then `reconcile.break.intake.dlt` (`KafkaConfig.java`, `KafkaProperties.java`).
- Delivery is at-least-once; consumers dedupe on `event-id` (README §8; no consumer in this repo).

## 6. Test counts

- Last green CI: run **#33**, workflow `ci`, `main`, commit 0393997, 2026-11-14 06:02 UTC: `https://github.com/<owner>/reconcile-ai/actions/runs/43564404616`
- From that run's log: unit (surefire) **96**, integration (failsafe) **143**, UI (Karma) **15**; all passed. Excluded by default: groups `openai`, `ollama`, `bedrock` (live tests).
- Local `mvn clean verify` on 2026-11-16, branch `feature/q2-gateway-governance` (Q2.3–Q2.7, uncommitted): unit **116**, integration **163**, 0 failures. Excluded groups now also `gateway`, `gateway-live`. No CI run yet for this state.
- After the ledger read, per-tenant credentials and shared rate limit work: unit **123**, integration **179** (to be filled from the build). No CI run yet for this state.
- Earlier: run #29 (9ee707d) had 96 / 140 / 15. The 3 new ITs are `EvalRunnerCitedFilesIT` (2) and one more case in `SupervisedInvestigationIT`.
- Other CI jobs in run #29: Angular, mvn clean verify, alert rules, k8s + image, terraform checks, terraform on LocalStack, SBOM.

## 7. Built / not built / built but not run live

| Item | State | Evidence |
|---|---|---|
| REST API, JWT, RLS, maker-checker, hash-chained audit, digest | Built | sections 1–3 |
| Hybrid retrieval (pgvector + tsvector, RRF k=60) | Built | `infra/ai/retrieval/HybridRetriever.java`, `RrfFusion.java` |
| Modes SINGLE_CALL, AGENT, SUPERVISED, SUPERVISED_PLANNED | Built, AGENT/SUPERVISED off by default. Supervised measured live 2026-11-13: decision rule not met | `dom/InvestigationMode.java`, `yml`, `docs/proof/phase4g-supervised-eval.md` |
| SUPERVISED_PLANNED refuses delegation after an agreed review | Built (outcome `ALREADY_REVIEWED`, not counted as a delegation); proven offline only, not re-measured live | `infra/ai/agent/SupervisedInvestigation.java` `delegate()`, `SupervisedInvestigationIT` |
| Resilience4j retry + circuit breaker (per instance) | Built | `LlmResilience.java` |
| Redis semantic cache, Redis SSE fan-out | Built, off by default | `infra/ai/cache/`, `infra/streaming/RedisProgressFanout.java` |
| Kafka intake + outbox relay | Built, off by default | `infra/messaging/` |
| OTel tracing bridge + OTLP exporter, Prometheus | Built, tracing off by default | `pom.xml`, `yml` |
| Caffeine L1 cache | Not built | none in code |
| Virtual threads | Enabled (5A, 2026-11-15); investigation executor still a bounded platform pool | `yml` `spring.threads.virtual.enabled`, `VirtualThreadsTenantIsolationIT`, `docs/adr/ADR-virtual-threads.md` |
| ai-gateway route (Q2.1–Q2.6) | Built, off by default; proven end to end against a fake gateway validated on the v1 schemas; **not yet run against the real gateway** | `infra/gateway/`, `app/gateway/`, V12, V13; `GatewayUnknownOutcomeIT`, `GatewayLedgerIT`, `GatewaySensitiveDataIT`, `GatewayBudgetIT`, `GatewayOutcomeGuardIT` |
| Real-gateway runs (Q2.7) | Built, opt-in, **not run**: `RealGatewayIT` (tag `gateway`, spike stack, stub provider, free) and `GatewayLiveProviderIT` (tag `gateway-live`, needs `GATEWAY_LIVE_APPROVED=yes`, paid) | `docs/proof/q2-7-gateway-live-runbook.md`, evidence template beside it |
| Gateway ledger read (`GET /api/v1/invocations/{id}`) | Built: reconcile reads the ledger itself for a UUID reference before recording (409 `INVOCATION_NOT_FOR_JOB`, 409 `LEDGER_ENTRY_NOT_FOUND`, 503 `GATEWAY_LEDGER_UNAVAILABLE`); V14 stores `ledger_execution_certainty` and `ledger_status`; `GET /investigations/{jobId}/gateway-calls` shows `ledgerResult`. Proven against the fake gateway; not run against the real gateway | `app/investigation/InvestigationService.java`, V14, `GatewayReconcileLedgerIT` (6); real-gateway read in `AbstractRealGatewayIT.java` (opt-in) |
| Per-tenant gateway credentials | Built (`reconcile.gateway.tenants`; empty map = shared client). Tested against a fake gateway and realm only; a real two-tenant run needs a second Keycloak client | `TenantGatewayTokensTest` (6), `GatewayPerTenantCredentialsIT` (4) |
| Rate limit shared through Redis (`RATE_LIMIT_STORE=redis`) | Built, default is per JVM. Real Redis in tests: two replicas share a bucket, 40 concurrent requests allow exactly 10, TTL, fallback to the local bucket | `RedisTokenBucketRateLimiterIT` (4), `SharedRateLimitIT` (2) |
| Executor gauges and KEDA overlay | Built: gauges `reconcile.investigations.executor.active`, `.queued`, `.capacity`; `k8s/overlays/keda` (ScaledObject 2 to 6, Prometheus trigger on active+queued at 3 per pod, CPU 70%, `RATE_LIMIT_STORE=redis`; base CPU HPA removed). Validated only: rendered and schema-checked with kubeconform (datreeio CRDs-catalog) in CI, not run on a cluster with KEDA | `InvestigationQueueMetricsTest` (1), `k8s/overlays/keda/`, CI job k8s |
| Auto-commit on AUTO_RESOLVE | Not built (downgraded to L1) | `ProposalService.java` |
| Idempotency-Key header | Not built | none in `api/` |
| problem+json errors | Not built | `ApiError.java` |
| Service-account client (inbound to reconcile-ai) | Not built | realm json |
| OpenAI chat | Run live (4B eval; 4G four-mode comparison, 40 cases, 2026-11-13) | `docs/proof/phase4-openai-eval.md`, `docs/proof/phase4g-supervised-eval.md` |
| Bedrock | Built, run only against a fake endpoint; live not run | `BedrockFakeEvalIT`, `BedrockEvalIT` tagged `bedrock` |
| AWS Terraform | Applied to LocalStack only; never to a real account | CI job `terraform apply + destroy against LocalStack` |
| Alertmanager e-mail | Mailpit only; real SMTP not run | README roadmap 4C |

## 8. Docs vs code disagreements

Status as of 2026-11-14 (Q0.3). "Annotated" means the private BRD/TDD keep the design text and add a **[Built: ...]** note with the code fact.

| # | Doc says → code does | Status |
|---|---|---|
| 1 | TDD §7 topics `automatch.proposals`, `recon.resolution.committed`, `.DLT` → code uses `reconcile.break.intake`, `reconcile.break.events`, `reconcile.break.intake.dlt` | TDD §1, §4, §7 annotated |
| 2 | BRD FR-02 and TDD say Kafka carries "break-proposal events" → intake creates a break only; `BreakSource.MATCH_PROPOSAL` exists but is unused | BRD FR-02, TDD §6, §7 annotated |
| 3 | BRD NFR-03, TDD §11, BRD Risks say circuit open → manual-review queue → code marks the job FAILED and the break FAILED_RETRYABLE (`InvestigationJobRunner.java:60, 294`) | annotated in all three places |
| 4 | BRD FR-08 lists auto-resolve as a route → even when enabled, AUTO_RESOLVE is persisted as L1_APPROVAL | BRD FR-08, TDD §6 annotated |
| 5 | README §6 had two count lines → CI shows one set | fixed in README at 0393997 (96 / 143 / 15, CI #33) |
| 6 | README "Amounts as JSON strings" → responses return JSON numbers; only requests and Kafka use strings | README §1 principle 6 and §7 table fixed |
| 7 | TDD §10 metric names (`llm.calls`, `agent.cache.hit_rate`, `hitl.*`) → code uses `reconcile.*`; no HITL queue-depth metric | TDD §10 annotated |
| 8 | TDD §8 says L1 Caffeine + L2 Redis → only L2 Redis exists | TDD §1 note added (§5.1, §8 were already annotated); BRD §8 and Risks annotated |
| 9 | TDD §13 `demo/run_golden_paths.sh` → only `scripts/demo-golden-path.ps1` exists | TDD §13 annotated |
| 10 | BRD §4.2 says Kubernetes manifests are untested → CI renders them with kustomize and schema-checks them with kubeconform (no cluster in CI) | BRD §4.2 and TDD §9 reworded |
| 11 | BRD/TDD describe "Repo A/Repo B" → only this repo exists | BRD header and TDD header annotated |
| 12 | Local JWT mode checks neither issuer nor audience | README security row fixed |
| 13 | TDD §9 "Idempotency keys on every mutating request" → no `Idempotency-Key` header; only unique break reference, evidence SHA-256 dedup and the one-running-job 409 | TDD §9 annotated (found in Q0.3) |
| 14 | TDD §4 API table (sync `/investigate`, `/approve`, `/reject`, `/breaks/{id}/audit`) → async jobs, one `/decision` endpoint, `/audit/aggregates/{id}` | TDD §4 annotated, points to §1 here (found in Q0.3) |

## 9. External recommender and EKA

**Submitting a recommendation from another system:**
- Exists: Kafka `reconcile.break.intake` (creates a break, no proposal); `POST .../evidence` with a user JWT (a recommendation document becomes evidence the model can cite).
- Does not exist: any endpoint or topic that accepts a proposal, root cause, confidence or route from outside. Proposals are created only by `ProposalService.persistProposal` inside the job runner. Any external input would still pass through `CitationVerifier` and `PolicyGateService` if routed through a new investigation input.

**Where an EKA knowledge-search call could plug in:**
- `infra/ai/retrieval/HybridRetriever.retrieve(tenantId, breakItemId, query)` is a concrete class, not an interface. Callers: `app/EvidenceSearchService.java`, `app/investigation/InvestigationJobRunner.java`, `infra/ai/agent/ReconTools.java`, `ReconToolsFactory.java`. A port would have to be extracted here. EKA results would also need chunk ids that `CitationVerifier` accepts.
- `infra/ai/agent/ReconTools.java`: a new read-only `@Tool` beside `searchEvidence` and `findSimilarResolvedBreaks` (tenant bound from the job).
- Existing outbound-sidecar pattern to copy: `infra/ai/pii/NerDetector.java` (interface, HTTP sidecar, timeout, fail-open or fail-closed flag).
- No existing interface named for knowledge search or for EKA.
