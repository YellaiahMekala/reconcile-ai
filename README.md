# reconcile-ai — Reconciliation Break Resolution Copilot

![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.16-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.1.8-blue.svg)
[![CI](https://github.com/YellaiahMekala/RAG-Integration-Reconciliation-Application/actions/workflows/ci.yml/badge.svg)](https://github.com/YellaiahMekala/RAG-Integration-Reconciliation-Application/actions/workflows/ci.yml)

> **Notice: independent demonstration project.**
> This is a generic, fictitious application built to demonstrate software engineering and banking-domain
> functional knowledge. It does not contain, reflect or derive from any client's data, code, functionality,
> architecture, designs or confidential information, and it is not affiliated with or endorsed by any current
> or former employer or client. All data (accounts, amounts, references, documents) is synthetic. Any
> resemblance to real people,organizations or transactions is coincidental. The development keys in this
> repository are throwaway, for local use only. The software is provided "as is" under the Apache License 2.0
> and is not intended for production use without independent security review and hardening.

A copilot for banking reconciliation operations. When a ledger amount and an external
(counterparty or bank) amount disagree, a **break** is raised. Analysts attach **evidence** such as
statements or SWIFT confirmations. An investigation retrieves that evidence, produces a resolution
proposal with citations, and a policy gate routes it to human approvers. **No break is resolved
without a human checker**, and the model never writes to the ledger.

---

## 1. Implementation approach

**Incremental, test-gated tiers.** Each tier has a done-when test, and the next tier does not start
until that test is green.

| Stage | Scope | Done-when |
|---|---|---|
| Foundation (Tier 0–1) | Break CRUD, evidence upload with hash dedup, JWT auth, RBAC, tenant isolation | `SecurityAndTenantIsolationIT` green (release-blocking) |
| AI investigation (Tier 2) | Evidence ingestion, hybrid retrieval, cited proposals, policy gate, maker-checker, hash-chained audit, SSE progress | `InvestigationFlowIT`, `GoldenPathIT`, `AuditChainIT` green |
| Platform integration | Shared token issuer, internal knowledge search, recommendation intake | Planned — see BRD platform requirements |

**Principles applied in the code** (each is commented at the point of use):

1. **Security by construction.** Repositories extend Spring Data's empty `Repository` interface, so tenant-blind
   methods like `findById` do not exist. No API accepts a tenant id.
2. **Fail closed.** Unknown JWT roles are dropped. A token without `tenant_id` is rejected with 401.
3. **No existence oracles.** Another tenant's resource returns 404, the same response as a missing resource.
4. **Deterministic gate.** The model proposes; Java code decides routing. Confidence, citation validity and
   variance thresholds are enforced in `PolicyGateService`, never by the model.
5. **Single resolution path.** Only `ResolutionCommitService` can move a break to RESOLVED, and only
   `ApprovalService` calls it. An architecture test enforces this.
6. **Money is exact.** `BigDecimal` via the `Money` value object. Amounts arrive as JSON strings. `double` is never used.
7. **Flyway owns the schema.** Hibernate only validates it (`ddl-auto: validate`).
8. **Real infrastructure in tests.** Postgres runs in Testcontainers (pgvector image) and is never mocked.

---

## 2. Features

| Area | Feature | Where |
|---|---|---|
| Breaks | Create, get, list (tenant-scoped, newest first, size capped) | `BreakController`, `BreakService` |
| Evidence | Multipart upload with SHA-256 dedup (201 new / 200 duplicate), list, hybrid search | `EvidenceController`, `EvidenceSearchController` |
| Ingestion | Tika text extraction, chunking, embeddings into pgvector | `infrastructure/ai/ingestion/EvidenceIngestor` |
| Retrieval | Dense (pgvector) + sparse (Postgres `tsvector`) fused with Reciprocal Rank Fusion | `infrastructure/ai/retrieval/HybridRetriever`, `RrfFusion` |
| Investigation | Async job with heartbeat lease, recovery of orphaned jobs, SSE progress (cross-instance via Redis pub/sub when `SSE_FANOUT=redis`) | `InvestigationController`, `InvestigationJobRunner`, `InvestigationRecovery` |
| Proposals | Structured output with citations; citations verified against retrieved chunk ids | `ProposalService`, `CitationVerifier` |
| Policy gate | Routes to MANUAL_REVIEW, L1 or L2 (auto-resolve implemented but off by default) | `PolicyGateService` |
| Approvals | L1/L2 queues, maker-checker, stricter-of-stored-and-recomputed tier | `ApprovalController`, `ApprovalService` |
| Audit | Hash-chained, append-only `audit_event`; verify endpoint for SRE_ADMIN | `HashChainAuditRecorder`, `AuditController` |
| Safety | Injection scanner before any model call; advisor chain (input guard, token usage) | `InputGuardAdvisor`, `TokenUsageAdvisor`, `InjectionScanner` |
| Security | RS256 JWT resource server, mandatory `tenant_id`, closed role set | `SecurityConfig`, `TenantContext`, `Role` |
| Tenant isolation (DB) | Postgres Row-Level Security on every tenant table and `vector_store`; app runs as a non-owner role; connections bound to the caller's tenant on checkout; fail closed | `V6__row_level_security.sql`, `TenantAwareDataSource`, `TenantScope` |
| Resilience | LLM retry (transient only, exponential backoff) inside a circuit breaker; fail fast when open | `infrastructure/ai/resilience/LlmResilience` |
| Privacy | PII masking of accounts, IBANs, cards (Luhn), emails and phones before every model call | `PiiMaskingAdvisor`, `PiiMasker` |
| Observability | Prometheus metrics (tokens, estimated cost, LLM latency, breaker, retries, PII counts); OpenTelemetry traces via OTLP; Grafana dashboard | `/actuator/prometheus`, `docker/observability/` |
| Messaging | Transactional outbox (`outbox_event`, same tx as the state change) relayed to Kafka; event-driven break intake with idempotency and a dead-letter topic; off by default | `infrastructure/messaging/`, `BreakIngestionService` |
| Cost | Redis semantic cache of verified model drafts (exact + semantic, per tenant, evidence-snapshot bound, fail-open); off by default | `infrastructure/ai/cache/` |
| UI | Angular 20 console: breaks, evidence + hybrid search, live SSE investigation progress, cited proposals, approvals, audit verify | `ui/` |
| Ops | Actuator health and Prometheus only; no error-message leakage | `/actuator/health`, `/actuator/prometheus` |

Endpoints are under `/api/v1`. Swagger UI is not part of this build.

---

## 3. Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 (records, pattern matching, text blocks) |
| Framework | Spring Boot 3.5.16, Spring MVC + `SseEmitter` (no WebFlux) |
| Security | Spring Security OAuth2 Resource Server, Nimbus JOSE (RS256) |
| Persistence | Spring Data JPA / Hibernate, PostgreSQL 16 with pgvector |
| Migrations | Flyway |
| AI | Spring AI 1.1.8 (OpenAI embeddings; mock-llm default chat model; Ollama profile) |
| Parsing | Apache Tika |
| Resilience | Resilience4j 2.4 (retry, circuit breaker), programmatic, no AOP |
| Ops | Spring Boot Actuator, Micrometer + Prometheus, Micrometer Tracing + OpenTelemetry (OTLP), Jaeger, Grafana |
| Frontend | Angular 20 (standalone components, signals, RxJS), Karma/Jasmine |
| Testing | JUnit 5, Testcontainers, AssertJ, Awaitility, spring-security-test |
| Build | Maven (surefire for `*Test`, failsafe for `*IT`) |

---

## 4. Architecture

```
api/            REST controllers, DTOs, GlobalExceptionHandler       (HTTP in/out only)
application/    BreakService, EvidenceService, InvestigationService,
                ProposalService, PolicyGateService, ApprovalService,
                ResolutionCommitService, audit recorder              (use cases, transactions)
domain/         BreakItem, Evidence, InvestigationJob, ResolutionProposal,
                ApprovalTask, Money, enums                           (no Spring web/AI imports)
infrastructure/
  persistence/  tenant-scoped repositories
  security/     SecurityConfig, TenantContext, CurrentUser, Role
  ai/           ChatClient config, advisors, retrieval, ingestion, prompt builder, mock LLM
  streaming/    SSE broadcaster and progress events
```

Request evaluation order for protected endpoints:

```
Bearer token ─► JwtDecoder: signature (RS256) + exp/nbf + tenant_id present ──✗──► 401
            ─► roles claim ─► Role enum (unknown dropped) ─► @PreAuthorize ──✗──► 403
            ─► Service: tenantId = TenantContext (JWT) ─► tenant-scoped query ──∅──► 404
            ─► 200 / 201 / 202
```

Investigation flow:

```
evidence (READY) ─► POST /investigations ─► job QUEUED
  ─► RETRIEVING (hybrid, tenant-filtered) ─► CALLING_MODEL (input guard, token budget)
  ─► VERIFYING (citations ⊆ retrieved ids) ─► ROUTING (policy gate)
      ├─ MANUAL_REVIEW
      ├─ L1 / L2 ─► approval task ─► maker-checker ─► ResolutionCommitService ─► RESOLVED
      └─ AUTO_RESOLVE (config off by default)
```

---

## 5. Run locally (Windows 11)

Requires JDK 21, Maven 3.9+ and Docker Desktop.

```powershell
# 1. Database (Postgres + pgvector)
docker compose -f docker/docker-compose.yml up -d

# 2. Full test gate: unit (surefire) + integration (failsafe, needs Docker)
mvn clean verify

# 3. Run the app (default profile: mock-llm, no external model calls)
mvn spring-boot:run

# 4. Mint a local token and call the API
mvn -q test-compile
$t = .\scripts\generate-local-jwt.ps1 -Subject analyst-1 -Tenant TENANT_A -Role RECON_ANALYST
curl.exe -s -X POST http://localhost:8080/api/v1/breaks `
  -H "Authorization: Bearer $t" -H "Content-Type: application/json" `
  -d '{\"breakReference\":\"BRK-1\",\"ledgerAmount\":\"50500.00\",\"externalAmount\":\"50000.00\",\"currency\":\"USD\"}'
```

The OpenAI key is read from `OPENAI_API_KEY` at startup outside the test profile. The automated suite does not
need it, because it uses a fake embedding model and the mock chat model.

### Run with a real model (OpenAI)

The default profile uses a deterministic mock model for chat, so the tests need no key. Embeddings always call
OpenAI `text-embedding-3-small` (a locked decision), so a live run of the app or the demo needs `OPENAI_API_KEY`,
which costs a fraction of a cent per run. To also let a real chat model draft the proposals, use the `openai` profile:

```powershell
$env:OPENAI_API_KEY = "<your key>"
mvn spring-boot:run "-Dspring-boot.run.profiles=openai"
```

Optional: `$env:OPENAI_CHAT_MODEL = "gpt-4o-mini"` (the default). The real-model smoke test is opt-in:

```powershell
mvn verify "-Dfailsafe.excludedGroups=ollama" "-Dit.test=OpenAiChatSmokeIT"
```

### Production hardening: try it

**Row-Level Security (second tenant guard).** Flyway runs as the schema owner (`DB_USER`/`DB_PASSWORD`, default
`reconcile`); the app connects as `reconcile_app` (`DB_APP_USER`/`DB_APP_PASSWORD`), which V6 creates with DML grants
only. Each connection checkout runs `set_config('app.tenant_id', <tenant from the JWT or an explicit job scope>)`;
policies then hide other tenants' rows even from a query with no tenant filter, and reject writes for another tenant.
No tenant bound means no rows. Cross-tenant platform work (outbox relay, recovery sweeper) uses an explicit, named
system scope that no JWT can claim. Test: `RowLevelSecurityIT`.

**Live progress across instances.** Run every instance with `SSE_FANOUT=redis` (and Redis from the `cache`
profile). Each progress event is published on the Redis channel `reconcile:progress`; every node delivers it to its
own open streams, so a browser may be connected to any node behind the load balancer. If Redis is unavailable the
node falls back to local delivery; the snapshot-on-subscribe and polling API still make the client correct.
Metric: `reconcile_sse_fanout_total{result}`. Test: `SseFanoutIT` (two real app instances, job on one, stream on the other).

**LLM retry and circuit breaker.** With the default mock model, evidence containing the marker text
`simulate transient glitch` fails the first model call and succeeds on retry; `simulate provider outage` fails
every attempt, so the job ends `FAILED` with reason `LLM_UNAVAILABLE` and the break becomes `FAILED_RETRYABLE`.
Five such failures in the window open the breaker: further investigations fail at once with `LLM_CIRCUIT_OPEN`,
without calling the model, until it half-opens after 30 s. Settings: `reconcile.ai.resilience.*`.
Fixtures: `src/test/resources/fixtures/evidence/timing-*.txt`. Tests: `LlmResilienceTest`, `LlmResilienceIT`.

**PII masking.** Every prompt passes `PiiMaskingAdvisor` (after the injection guard, before token accounting).
`reconcile_pii_masked_total{type=...}` counts what was masked; the values are never logged. Disable with
`reconcile.ai.pii-masking.enabled=false`. Tests: `PiiMaskerTest`.

**Semantic cache (Redis).** `docker compose -f docker/docker-compose.yml --profile cache up -d`, then run with
`CACHE_ENABLED=true`. Only citation-verified drafts are stored. A hit replaces the model call only; normalisation,
citation check and the policy gate still run. Hits need the same tenant AND the identical retrieved-chunk set, so new
evidence always forces a fresh call; a suspect analyst note bypasses the cache. Redis errors are misses, never
failures. Metrics: `reconcile_llm_cache_total{result=hit_exact|hit_semantic|miss|store|error}`; hits are recorded in
`llm_call` as `CACHE_HIT` with 0 tokens. Tests: `SemanticCacheIT` (real Redis), `SemanticCacheMathTest`.

**Kafka outbox + intake.** `docker compose -f docker/docker-compose.yml --profile kafka up -d`, run with
`KAFKA_ENABLED=true`. Break creation (REST or Kafka), proposal routing and resolution write an `outbox_event` row in the
same transaction; `OutboxRelay` publishes them to `reconcile.break.events` (key = break id, headers `event-id`,
`event-type`, `tenant-id`; at-least-once, `FOR UPDATE SKIP LOCKED`, per-key order preserved). Send a JSON message to
`reconcile.break.intake` to create a break:

```json
{"tenantId":"TENANT_DEMO","breakReference":"KAFKA-1","ledgerAmount":"1200.50","externalAmount":"1000.00","currency":"USD"}
```

Duplicates are ignored (idempotent on tenant + reference); malformed messages go to `reconcile.break.intake.dlt`
without retry. Metrics: `reconcile_outbox_published_total`, `reconcile_intake_total{outcome}`. Tests: `KafkaOutboxIT`
(real broker). With Kafka off, events still accumulate in the outbox table and nothing else changes.

**Web UI.** See [`ui/README.md`](ui/README.md). In short: run the API with
`-Dspring-boot.run.profiles=mock-llm,demo-login`, then `cd ui; npm ci; npm start` and open http://localhost:4200.

**Observability.**

```powershell
docker compose -f docker/docker-compose.yml --profile observability up -d   # Postgres, Jaeger, Prometheus, Grafana
$env:TRACING_ENABLED = "true"; mvn spring-boot:run
curl.exe -s http://localhost:8080/actuator/prometheus | Select-String reconcile_llm
```

- Grafana: http://localhost:3000, dashboard *reconcile-ai: LLM cost, latency and resilience* (estimated spend,
  tokens/min, LLM p95, breaker state, retries, PII masked, HTTP p95, LLM errors).
- Jaeger: http://localhost:16686, service `reconcile-ai`. Each investigation job is one span (`investigation-job`)
  with the Spring AI chat-client and model spans nested under it, linked to the `POST /investigations` request.
- Cost prices: `LLM_PROMPT_USD_PER_MILLION`, `LLM_COMPLETION_USD_PER_MILLION` (defaults: gpt-4o-mini list price).

---

## Demo

With the app running, this script walks the whole golden path against the live API (break, evidence,
investigation, policy route, approval, RESOLVED, audit chain check):

```powershell
.\scripts\demo-golden-path.ps1 -Pause      # -Pause waits for Enter before each step, for screen recording
```

---

## 6. Test suite

Current counts: **58 unit tests** (surefire) and **72 integration tests** (failsafe, real Postgres via Testcontainers).
Tests tagged `openai` and `ollama` need a real key or a running Ollama and are excluded from the default build.

| Class | Type | Proves |
|---|---|---|
| `MoneyAndVarianceTest` | unit | BigDecimal money, currency mismatch rejected, variance = abs difference |
| `RoleTest` | unit | Only the four known roles parse |
| `PolicyGateServiceTest` | unit | Routing thresholds: citations, root cause, confidence, currency, variance |
| `CitationVerifierTest`, `InjectionScannerTest` | unit | Citation validity; injection pattern detection |
| `ResolutionPathArchitectureTest` | unit | `ResolutionCommitService` has exactly one caller |
| `SecurityAndTenantIsolationIT` | IT | 401/403/404 rules, client-supplied tenant ignored — **release-blocking** |
| `BreakAndEvidenceIT` | IT | Create/fetch, duplicate reference 409, evidence dedup |
| `EvidenceIngestionIT`, `HybridRetrievalIT` | IT | Ingestion into pgvector; tenant-filtered hybrid retrieval |
| `InvestigationFlowIT`, `GoldenPathIT` | IT | Evidence → cited proposal → route → approval → RESOLVED; injection quarantine |
| `InvestigationRecoveryIT`, `InvestigationStreamingIT` | IT | Orphaned job recovery; SSE lifecycle and tenant isolation |
| `AuditChainIT` | IT | Hash chain verifies; append-only trigger rejects updates |
| `LlmResilienceTest`, `LlmResilienceIT` | unit, IT | Transient-only retry, breaker opens and fails fast, outage fails the job retryably |
| `PiiMaskerTest` | unit | Identifiers masked; amounts, dates, references and chunk ids untouched; advisor masks what the model sees |
| `KafkaOutboxIT` | IT | Outbox rows published with headers; intake creates one break despite redelivery; malformed to DLT |
| `SemanticCacheIT` | IT | Exact and semantic hits; tenant isolation; evidence-snapshot rule; suspect note bypass |
| `DemoLoginIT` | IT | Persona tokens pass normal JWT validation; roles still enforced; unknown persona rejected |
| `RowLevelSecurityIT` | IT | Unscoped connection sees nothing; queries without a tenant filter still isolated; cross-tenant insert rejected by Postgres; app role cannot disable RLS; a JWT cannot claim system scope |
| `SseFanoutIT` | IT | Two app instances share Postgres and Redis; a stream on node 2 receives every stage of a job running on node 1 |
| `ObservabilityIT` | IT | Prometheus exposes LLM/breaker/HTTP series and no tenant ids; other actuator endpoints closed |

---

## 7. Key design decisions

| Decision | Why |
|---|---|
| `Repository` instead of `JpaRepository` | Removes tenant-blind inherited methods at compile time |
| Custom `JwtDecoder` bean | A token without a tenant would otherwise cause a 500 |
| `@EnableMethodSecurity` | Without it, `@PreAuthorize` is silently ignored |
| No catch-all exception handler | It would turn security 403s into 500s |
| 404 instead of 403 cross-tenant | 403 confirms that the resource exists |
| Model proposes, Java decides | One hallucinated sentence must not cause a ledger write |
| Hybrid RRF retrieval | Narrative text favours dense search; reference codes and amounts favour exact match |
| Singleton Testcontainers base | `@Container` in a shared base class breaks the second IT class through context caching |
| Amounts as JSON strings | Avoids binary floating-point on the wire |
| Self-signed RSA dev keys | No authorization server to run locally; swapping in a real IdP only changes the public key config |

## 8. Known limitations

- SSE fan-out over Redis pub/sub is fire-and-forget: an event missed during a Redis blip is recovered by the next event or the snapshot on reconnect, not replayed.
- Kafka delivery is at-least-once; consumers must dedupe on the `event-id` header. Intake trusts the tenant named in the message, so topic ACLs are the trust boundary.
- The UI signs in with demo personas (`demo-login` profile, throwaway key); there is no real identity provider.
- PII masking covers structured identifiers only (it is not DLP): names and free-form disclosures pass through.
- The circuit breaker is per instance; the cost metric is an estimate from list prices, not an invoice.
- Auto-resolve is implemented but disabled by configuration (ADR-9).
- The RSA keys in `src/test/resources/jwt` and `scripts/jwt` are throwaway development material. Do not reuse them outside local development.

---

## License

Copyright (c) 2026 Yellaiah Mekala. Licensed under the Apache License 2.0; see [LICENSE](LICENSE).

---

## Author

**Yellaiah Mekala** — Senior Java backend engineer focused on AI integration.

- GitHub: [github.com/YellaiahMekala](https://github.com/YellaiahMekala)
