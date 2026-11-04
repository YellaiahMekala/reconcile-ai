# reconcile-ai — Reconciliation Break Resolution Copilot

![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.16-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.1.8-blue.svg)
[![CI](https://github.com/YellaiahMekala/RAG-Integration-Reconciliation-Application/actions/workflows/ci.yml/badge.svg)](https://github.com/YellaiahMekala/RAG-Integration-Reconciliation-Application/actions/workflows/ci.yml)

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
| Investigation | Async job with heartbeat lease, recovery of orphaned jobs, SSE progress | `InvestigationController`, `InvestigationJobRunner`, `InvestigationRecovery` |
| Proposals | Structured output with citations; citations verified against retrieved chunk ids | `ProposalService`, `CitationVerifier` |
| Policy gate | Routes to MANUAL_REVIEW, L1 or L2 (auto-resolve implemented but off by default) | `PolicyGateService` |
| Approvals | L1/L2 queues, maker-checker, stricter-of-stored-and-recomputed tier | `ApprovalController`, `ApprovalService` |
| Audit | Hash-chained, append-only `audit_event`; verify endpoint for SRE_ADMIN | `HashChainAuditRecorder`, `AuditController` |
| Safety | Injection scanner before any model call; advisor chain (input guard, token usage) | `InputGuardAdvisor`, `TokenUsageAdvisor`, `InjectionScanner` |
| Security | RS256 JWT resource server, mandatory `tenant_id`, closed role set | `SecurityConfig`, `TenantContext`, `Role` |
| Ops | Actuator health only; no error-message leakage | `/actuator/health` |

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
| Ops | Spring Boot Actuator (health only) |
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

---

## Demo

With the app running, this script walks the whole golden path against the live API (break, evidence,
investigation, policy route, approval, RESOLVED, audit chain check):

```powershell
.\scripts\demo-golden-path.ps1 -Pause      # -Pause waits for Enter before each step, for screen recording
```

---

## 6. Test suite

Current counts: **47 unit tests** (surefire) and **60 integration tests** (failsafe, real Postgres via Testcontainers).
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

- Not implemented: Kafka, Redis, multi-instance scale-out, PII masking, a UI, and a daily digest.
- Auto-resolve is implemented but disabled by configuration (ADR-9).
- The RSA keys in `src/test/resources/jwt` and `scripts/jwt` are throwaway development material. Do not reuse them outside local development.

---

## License

Copyright (c) 2026 Yellaiah Mekala. Licensed under the Apache License 2.0; see [LICENSE](LICENSE).

---

## Author

**Yellaiah Mekala** — Senior Java backend engineer focused on AI integration.

- GitHub: [github.com/YellaiahMekala](https://github.com/YellaiahMekala)
