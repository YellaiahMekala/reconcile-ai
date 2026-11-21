# reconcile-ai: feature testing guide

Scope: F1 to F20 cover features tested between 2026-11-16 and 2026-11-18 (platform stages Q1, Q2, Q3, Q4 and the D1
items). F21 to F31 cover the Phase 1 to Phase 5 features built earlier, with an evidence map for them. F32 to F43
(added 2026-11-19) cover the changes of the last seven days that had no entry yet: RA-3, INT-4, the view-only license and
headers, the Trivy fixes, virtual threads, the gateway adapter, API hygiene, KEDA on kind, the similar-breaks tool, the
repository chores and the RERO status. The evidence map is the last section. Every number below comes from a register row in `platform/evidence/EVIDENCE_REGISTER.md` (E-ids), from a proof
document in `docs/proof/`, or from a log in `docs/proof/`.

Run types used below:

- **Default build**: `mvn clean verify` (unit with surefire, integration with failsafe on Testcontainers). Free, runs in CI.
- **Opt-in, free**: a JUnit tag excluded from the default build (`eka`, `gateway`). Needs a local stack, no model call.
- **Opt-in, paid, approved**: tags `gateway-live` and `platform-live`. A real model call through the gateway. Never in CI.

Secrets are always environment variables, listed here by name only.

## Index

| ID | Feature | Register | Run type |
|---|---|---|---|
| [F1](#f1-knowledge-pre-step-q11-to-q15) | Knowledge pre-step (Q1.1 to Q1.5) | E-25 | default build |
| [F2](#f2-real-eka-tenant-isolation-q16) | Real EKA tenant isolation (Q1.6) | E-26 | opt-in, free |
| [F3](#f3-concurrent-eka-lookups-q17) | Concurrent EKA lookups (Q1.7) | E-26 | opt-in, free |
| [F4](#f4-gateway-unknown-outcome-q23) | Gateway unknown outcome (Q2.3) | E-18 | default build |
| [F5](#f5-gateway-ledger-sensitive-data-and-budget-q24-to-q26) | Gateway ledger, sensitive data, budget (Q2.4 to Q2.6) | E-19 | default build |
| [F6](#f6-ledger-read-per-tenant-credentials-shared-rate-limit-keda) | Ledger read, per-tenant credentials, shared rate limit, KEDA overlay | E-21 | default build + CI schema check |
| [F7](#f7-review-fixes-and-the-flaky-recovery-sweep-test) | Review fixes and the flaky recovery-sweep test | E-22, E-26 | default build |
| [F8](#f8-live-gateway-run-q27) | Live gateway run (Q2.7) | E-27 | opt-in, paid, approved |
| [F9](#f9-card-masking-alignment-fix) | Card-masking alignment fix | E-28 | default build |
| [F10](#f10-golden-path-and-eka-down-demo-q42-q45) | Golden path and EKA-down demo (Q4.2, Q4.5) | E-28 | opt-in, paid, approved |
| [F11](#f11-integrated-evaluation-q43) | Integrated evaluation (Q4.3) | E-29 | opt-in, paid, approved |
| [F12](#f12-knowledge-ablation-q43) | Knowledge ablation (Q4.3) | E-30 | opt-in, paid, approved |
| [F13](#f13-rero-integration-on-fakes-q32-and-q35) | RERO integration on fakes (Q3.2, Q3.5) | E-31 | default build |
| [F14](#f14-q37-rero-keepcut-evaluation) | Q3.7 RERO keep/cut evaluation | E-32 | done, CUT (paid, run) |
| [F15](#f15-real-gateway-and-per-tenant-realm-clients-pq111-pq112) | Real gateway and per-tenant realm clients (PQ1.1.1, PQ1.1.2) | E-20, E-34 | opt-in, free |
| [F16](#f16-golden-path-and-eka-down-in-ci-pq116) | Golden path and EKA-down in CI (PQ1.1.6) | E-35 | default build |
| [F17](#f17-relevance-filter-re-measure-pq124) | Relevance filter re-measure (PQ1.2.4) | E-36 | opt-in, paid, approved |
| [F18](#f18-l1-cache-in-front-of-redis-d121) | L1 cache in front of Redis (D1.2.1) | none yet (CI `ci` 44333738072 green) | default build |
| [F19](#f19-read-only-mcp-server-d122) | Read-only MCP server (D1.2.2) | none yet | default build |
| [F20](#f20-break-specific-knowledge-query-d1212) | Break-specific knowledge query (D1.2.12) | none yet | default build; paid re-measure approved and run |
| [F21](#f21-foundation-security-tenant-isolation-breaks-and-evidence-tier-0-to-1) | Foundation: security, tenant isolation (Tier 0 to 1) | E-01, E-02, E-13 | default build |
| [F22](#f22-investigation-pipeline-tier-2) | Investigation pipeline (Tier 2) | E-03, E-04 | default build |
| [F23](#f23-production-hardening-resilience-pii-outbox-digest-rate-limit-api-hygiene) | Production hardening | E-16 | default build |
| [F24](#f24-audit-anchoring-signed-chain-heads-in-s3-object-lock) | Audit anchoring | E-54 | default build; real S3 pending |
| [F25](#f25-evaluation-harness-phase-4a) | Evaluation harness (4A) | E-10 | gate in build; live paid |
| [F26](#f26-investigation-agent-4b-and-supervisor-agents-4g) | Agent (4B) and supervisor agents (4G) | E-10 | default build; live paid |
| [F27](#f27-alert-delivery-phase-4c) | Alert delivery (4C) | E-55 | promtool; real SMTP pending |
| [F28](#f28-amazon-bedrock-adapter-phase-4d) | Amazon Bedrock adapter (4D) | E-56 | fake in build; live pending |
| [F29](#f29-aws-deployment-with-terraform-phase-4e) | AWS deployment with Terraform (4E) | E-57 | LocalStack; real apply pending |
| [F30](#f30-observability-dashboard-and-semantic-cache-savings-phase-5c-and-5d) | Dashboard and cache savings (5C, 5D) | E-14, E-15 | local run |
| [F31](#f31-console-ui-and-kubernetes-manifests) | Console UI and Kubernetes manifests | E-33, E-21, E-44 | CI |
| [F32](#f32-ra-3-missing-entry-phrasing-in-the-relevance-filter) | RA-3 missing-entry phrasing in the relevance filter | E-58 | unit test; paid re-measure not run |
| [F33](#f33-int-4-concurrent-investigations-through-the-gateway-and-eka) | INT-4 concurrent investigations through the gateway and EKA | E-63 | opt-in, free, local stack |
| [F34](#f34-view-only-license-ai-use-policy-and-source-headers) | View-only license, AI use policy, source headers and canary | E-58 (UI and unit re-check) | default build, UI specs |
| [F35](#f35-trivy-image-fix-remove-the-unused-pebble-binary) | Trivy image fix: remove the unused pebble binary | none (CI run 44499744262) | CI image job |
| [F36](#f36-java-21-virtual-threads-for-request-handling-5a) | Java 21 virtual threads (5A) | E-13 | default build |
| [F37](#f37-gateway-adapter-idempotency-key-and-refusal-mapping-q21-q22) | Gateway adapter, idempotency key, refusal mapping (Q2.1, Q2.2) | E-16, E-17 | default build |
| [F38](#f38-latest-job-endpoint-idempotency-key-and-problemjson-d112-d114) | Latest-job endpoint, Idempotency-Key, problem+json (D1.1.2, D1.1.4) | none yet (CI 44293523261) | default build |
| [F39](#f39-trivy-gate-that-fails-the-build-d113) | Trivy gate that fails the build (D1.1.3) | E-40 | CI image job |
| [F40](#f40-keda-overlay-run-on-a-kind-cluster-pq121) | KEDA overlay run on kind (PQ1.2.1) | E-44 | local with stubs |
| [F41](#f41-similar-breaks-tool-contract-pq129) | Similar-breaks tool contract (PQ1.2.9) | E-46 | default build |
| [F42](#f42-repository-and-documentation-chores-of-the-week) | CI minutes, guide, claim alignment, portfolio page | E-47 | docs, CI config |
| [F43](#f43-rero-the-sibling-service-cut-off-by-default) | RERO sibling service (cut, off by default) | E-31, E-32 | RERO `pytest` |

## F1 Knowledge pre-step, Q1.1 to Q1.5

**What and why.** Before the one model call, the service asks the enterprise knowledge assistant (EKA) for the
operating procedures that match the break. Analysts get the relevant procedure in the prompt. The risk is that a
procedure is mistaken for evidence, that another tenant's document leaks in, or that an EKA outage stops investigations.

**Design decision** (`docs/adr/ADR-Q1-knowledge-prestep.md`). The service, not the model, fetches knowledge. One
`POST /api/v1/search` per job, so the model call stays a single call that can go through the gateway. Hits are
"procedures consulted", stored as `kb:` refs apart from evidence chunk ids, so the citation verifier can never accept
them. Four stored outcomes: `OK`, `EMPTY`, `UNAVAILABLE`, `REJECTED`. Fail-open by default
(`KNOWLEDGE_FAIL_CLOSED=false`). Per-tenant service tokens, no shared client. Off by default (`KNOWLEDGE_ENABLED`).
Admin document endpoints are not built, by decision.

**Tests that prove it.**

| Class | Asserts |
|---|---|
| `EkaKnowledgeClientTest` (15) | Stub validated against EKA `openapi.json`; every error row; IBAN masked on the wire; suspect hit dropped, content not logged; 401/403 is `UNAVAILABLE AUTH` |
| `TenantServiceTokensTest` (12) | Token cache and refresh; `tenant_id` must equal the job tenant; typed failures; secrets only from the environment |
| `KnowledgeResultTest` (4) | The four outcomes and their reason codes |
| `KnowledgeNeverCitedTest` (4) | A `kb:` ref is never accepted by the citation verifier |
| `InvestigationKnowledgeQueryTest` (3) | How the EKA query is built (generic vocabulary plus the analyst note) |
| `KnowledgeArchitectureTest` (3) | Only `/api/v1/search` exists in main code; agent modes do not use knowledge |
| `KnowledgeOutcomeIT` (7) | One job per outcome on real Postgres; refs stored apart from evidence; tenant without a client sends nothing; each tenant sees only its own procedures; no break identifiers or amounts go to EKA |
| `KnowledgeFailClosedIT` (2) | `UNAVAILABLE` fails the job retryably with no proposal; `EMPTY` still continues |

**How to run.** `mvn clean verify`. Free. Flags: `KNOWLEDGE_ENABLED`, `KNOWLEDGE_FAIL_CLOSED`.

**Result.** 166 unit / 193 IT, 0 failures; CI green on main, `ci` #52 on `acdafe6` (2026-11-18, 6m56s). E-25.

**Failure and edge cases.** EKA down: job still `SUCCEEDED` with `UNAVAILABLE` (open) or `FAILED_RETRYABLE` (closed). A
hit that looks like an injection is dropped and only the rule name is logged.

**Honest limits.** Proven against a stub built from EKA's OpenAPI document, not the real EKA (that is F2). No claim
about answer quality (that is F11 and F12).

**Interview questions.**

- *Why a pre-step and not an agent tool?* The gateway has no tool calling, so an agent tool cannot run on the governed
  path (as of 2026-11-19 this is being addressed by D1.3.1, the gateway tool-calling passthrough, contract
  `tool-calling.v1`; until it lands, ops-agent bypasses the gateway). I made retrieval deterministic, which also removes the model's chance to choose a wrong or cross-tenant query.
- *Why fail-open?* A degraded procedure lookup should not stop an investigation a person will still approve. The
  outcome is stored and shown, and I kept `KNOWLEDGE_FAIL_CLOSED` for regulated tenants.
- *How do you stop a procedure being cited as proof?* Separate refs, a prompt rule, and `KnowledgeNeverCitedTest`.

## F2 Real EKA tenant isolation, Q1.6

**What and why.** Tenant B must never see tenant A's documents. This is the blocking test for turning knowledge on.

**Design decision.** EKA isolates by caller identity, so each tenant has its own client in the platform realm and the
token `tenant_id` claim must match. The test uses reconcile-ai's own token and search code, not a hand-made request.

**Tests.** `RealEkaIsolationIT` (tag `eka`), 3 tests: `tenantAFindsItsProcedureThroughTheReconcileClient` (rules out an
empty result caused by a broken token), `tenantBSeesNothingOfTenantA` (exact marker text searched as B gives `EMPTY`;
GET on A's document id is 404; B's listing does not contain it).

**How to run** (runbook `docs/proof/q1-6-eka-isolation-runbook.md`). Platform Keycloak on 8190 and EKA edge on 8080
first. Env names: `EKA_LIVE_BASE_URL`, `KNOWLEDGE_TOKEN_URI`, `KC_SVC_TENANT_A_SECRET`, `KC_SVC_TENANT_B_SECRET`.

```
mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway,gateway-live" -Dit.test=RealEkaIsolationIT
```

Free, no model call.

**Result.** 3/3 passed, 2026-11-18, EKA `3ae3100` edge profile, Keycloak 26.3. E-26.

**Failure and edge cases.** Without `EKA_LIVE_BASE_URL` the test skips; a skip is not a pass. If B ever sees A's text,
do not enable knowledge anywhere. The document stays `ready` only if the EKA ingestion worker runs.

**Honest limits.** One run on one laptop; one pair of tenants; synthetic document.

**Interview questions.**

- *How did you prove isolation rather than assume it?* I searched tenant A's exact text as tenant B against the real
  edge, and also checked the 404 on the document id and the listing, three independent channels.
- *Why include the positive test?* A pass on "B sees nothing" means little if A also sees nothing.

## F3 Concurrent EKA lookups, Q1.7

**What and why.** Ten investigations at once must not break the knowledge step. A rate limit or timeout must surface as
a typed outcome, not an exception.

**Tests.** `RealEkaIsolationIT.tenConcurrentLookupsAllReturnATypedOutcome`: ten parallel searches; each is `OK` or
`UNAVAILABLE` with no exception and no hit on `UNAVAILABLE`.

**How to run.** Same as F2.

**Result.** 10 of 10 `OK`; latencies 199, 322, 330, 343, 356, 367, 375, 365, 374, 380 ms. E-26.

**Honest limits.** Not a capacity claim: one run, one laptop, one tenant. An `UNAVAILABLE` would have been recorded
as a result, not a test failure.

**Interview question.** *What did you learn from it?* That ten parallel lookups fit inside the edge's limits with a
latency of about 200 to 380 ms, and that I should not extrapolate that to a capacity number.

## F4 Gateway unknown outcome, Q2.3

**What and why.** If the gateway times out after sending, nobody knows whether the model was called and billed. A
blind retry could double-act. This is release-blocking for the gateway route.

**Design decision.** Exactly one request per job under `recon:<jobId>:investigate:1`. A 504 `UNKNOWN_OUTCOME` or a 409
`IDEMPOTENCY_UNKNOWN_OUTCOME` fails the job flagged `outcome_unknown`. A re-run is refused (409
`OUTCOME_UNRECONCILED`) until a second person reconciles via `POST /investigations/{jobId}/reconcile`. The re-run is a
new job with a new key.

**Tests.** `GatewayUnknownOutcomeIT` (2): whole app, real Postgres, fake gateway (`FakeGatewayServer`) whose replies
are validated against the v1 schemas; asserts one request, flagged job, blocked re-run. `GatewayOutcomeGuardIT` (5):
roles, a different person than the starter, identifier-shaped reference, other tenant 404, recovery on the direct route
never flags a job.

**How to run.** `mvn clean verify`. Free.

**Result.** Local pass on 2026-11-16 (E-18); the class is part of the default build that is green in CI.

**Edge cases.** Same person tries to reconcile: 409 `RECONCILER_MUST_DIFFER`. Recovery of an orphaned job on the
direct (non-gateway) route does not set the flag.

**Honest limits.** Fake gateway; the real gateway's unknown-outcome behaviour is not exercised here.

**Interview questions.**

- *Why not just retry?* A retry on an unknown outcome can double-charge or double-act. I make the unknown state
  explicit and require a second person to resolve it.
- *Why a new key on re-run?* Reusing the key would replay the unknown call; a new job and key is a deliberate new attempt.

## F5 Gateway ledger, sensitive data and budget, Q2.4 to Q2.6

**What and why.** Every model call must be traceable to a job, no raw IBAN or card number may leave, and a budget
refusal must cost nothing and be retryable.

**Design decision.** `gateway_call` (V13) links `jobId -> key -> invocationId`. A success also writes an `llm_call` row
with model `ai-gateway`. `RISK_BLOCKED` and `SENSITIVITY_BLOCKED` send the break to manual review with no proposal;
`BUDGET_BLOCKED` is one request, no spend, `FAILED_RETRYABLE`.

**Tests.**

| Class | Asserts |
|---|---|
| `GatewayLedgerIT` (7) | Link for success, budget, risk, sensitivity (invocation id resolvable on the ledger); auth and schema rejections logged without an id; `llm_call` row with the gateway's token counts |
| `GatewaySensitiveDataIT` (3) | IBAN and card reach the gateway as `[IBAN]` and `[CARD_NUMBER]`; blocked outcomes create no proposal and go to manual review |
| `GatewayBudgetIT` (2) | 429 `BUDGET_BLOCKED` and `RATE_LIMITED`: one request, no spend, not outcome-unknown; a re-run succeeds when budget returns |
| `HttpGatewayChatAdapterTest` (7), `GatewayFailureTest` (4) | Request matches `chat-request.v1.json`; fake replies match response and error schemas; one retry only on not-sent codes; refusal codes map to typed reasons |

**How to run.** `mvn clean verify`. Free.

**Result.** 116 unit / 163 IT, 0 failures on 2026-11-16 (before later work). E-19.

**Honest limits.** Fake gateway; the real-gateway counterpart is F8.

**Interview questions.**

- *How do you prove a bad card number never leaves?* The IT reads what the fake gateway received and asserts the
  placeholders, not the raw values.
- *Why manual review rather than retry on a risk block?* Retrying the same data gets the same refusal; a person must decide.

## F6 Ledger read, per-tenant credentials, shared rate limit, KEDA

**What and why.** Reconcile checks the gateway ledger before recording an unknown outcome; each tenant uses its own
gateway identity; the rate limit holds across replicas; queue depth can drive autoscaling.

**Tests.** `GatewayReconcileLedgerIT` (6): ledger fields on success, the refusals `INVOCATION_NOT_FOR_JOB`,
`LEDGER_ENTRY_NOT_FOUND`, `GATEWAY_LEDGER_UNAVAILABLE`, and the `gateway-calls` view. `TenantGatewayTokensTest` (6) and
`GatewayPerTenantCredentialsIT` (4): wrong tenant fails before sending, no fallback to a shared client.
`RedisTokenBucketRateLimiterIT` (4): real Redis, two replicas share one bucket, 40 concurrent requests allow exactly
10, fallback to a local bucket when Redis is unreachable. `SharedRateLimitIT` (2). `InvestigationQueueMetricsTest` (1).
CI step `kubeconform` on `k8s/base`, `k8s/overlays/kind`, `k8s/overlays/keda`.

**How to run.** `mvn clean verify`. Free.

**Result.** Local pass on `e15a7e1`, 2026-11-18. E-21.

**Honest limits.** In CI KEDA is a schema check only. On 2026-11-19 the overlay was also run once on a kind cluster with
KEDA 2.21.1, but with a stub app and a stub Prometheus (E-44, `docs/proof/pq1-2-1-keda-kind-2026-11-19.md`), so the real
image, real Prometheus metrics and the CPU trigger are still unproven. Per-tenant credentials ran against a fake gateway
and fake realm in CI, and once against the real realm and gateway with the stub provider (F15).

**Interview questions.**

- *How do you test a distributed rate limit?* Real Redis in Testcontainers, 40 concurrent requests, exactly 10 allowed.
- *Did you prove autoscaling?* Partly. The overlay is schema-checked in CI and scaled 2 to 4 to 6 on kind against a stub Prometheus value; the real image under real load is not done.

## F7 Review fixes and the flaky recovery-sweep test

**What and why.** Review found governance holes. While the gateway route is on, agent and supervised modes call the
provider directly, so they are refused. A stalled node's late result must not undo recovery. Token failures are typed.

**Tests.** `GatewayReviewFixesIT`: `ungovernedModesAreRefusedWhileTheGatewayRouteIsOn` (409 `MODE_NOT_GOVERNED`),
`aLateResultFromAStalledNodeDoesNotUndoRecovery`, `recoveryFlagsUnknownOutcomeOnlyForGatewayJobs`. Also
`JpaGatewayCallLogTest` (sanitised codes).

**Flaky test.** CI run #54 failed once in `GatewayReviewFixesIT`. Cause: all cached Spring contexts share one test
database, and a background recovery sweep from another test class's context could recover the orphaned job first and
record it differently. Fix (commit `5a49430`): `AbstractPostgresIT` pushes `reconcile.investigation.sweep-interval-ms`
to 3600000, because tests call `recovery.sweep()` themselves. CI #58 was green afterwards.

**How to run.** `mvn clean verify`. Free.

**Result.** 125 unit / 184 IT at `d0da196`, 2026-11-18 (E-22); CI green again after the fix (E-26 notes #54 and #56).

**Honest limits.** The fix removes a race in the test setup; it does not prove the sweep itself is race-free across nodes.

**Interview question.** *How did you handle a flaky test?* I read the failing run, found a shared-database timer racing
the test, removed the timer from the test configuration instead of adding retries, and confirmed with a green run.

## F8 Live gateway run, Q2.7

**What and why.** Fakes cannot prove the real gateway accepts our token, key and request shape. One paid call does.

**Tests.** `GatewayLiveProviderIT` (tag `gateway-live`). `RealGatewayIT` (tag `gateway`, stub provider, free) is the
free sibling. Runbook: `docs/proof/q2-7-gateway-live-runbook.md`.

**How to run.** Env names: `GATEWAY_CLIENT_SECRET`, `GATEWAY_LIVE_APPROVED=yes` (the gate; unset it afterwards),
optional `GATEWAY_BASE_URL`, `GATEWAY_TOKEN_URI`, `GATEWAY_CLIENT_ID`, `GATEWAY_TOKEN_SCOPE`. Paid, approved.

```
mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway" "-Dtest=NONE" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=GatewayLiveProviderIT"
```

**Result (2026-11-18, once).** Job SUCCEEDED, attempt 1, http 200, gateway status `OK`, 719 in / 111 out tokens, 3919
ms. Ledger: 200 `OK` `EXECUTED`, cost 0.00017445 USD. Our `llm_call` row carries the same counts. E-27.

**Honest limits.** One investigation. Nothing about answer quality, budgets or policy on the real gateway.

**Interview question.** *What does one live call buy you?* Proof the wiring is real: token accepted, idempotency key
accepted, invocation id resolves on the ledger under our token, and our token counts equal the gateway's.

## F9 Card-masking alignment fix

**What and why.** The first golden-path attempt was refused by the gateway with 422 `SENSITIVITY_BLOCKED` (detector
`PAYMENT_CARD`, cost 0). The break reference held a Luhn-valid 14-digit timestamp. The gateway flags any such run not
next to another digit, while our masker skipped runs glued to a letter or dash.

**Design decision.** Align with the stricter detector: `PiiMasker` masks a contiguous Luhn-valid run of 13 to 19 digits
even inside a reference. UUIDs cannot match (longest segment is 12 digits).

**Tests.** `PiiMaskerTest`, two added methods:
`luhnValidDigitRunInsideAReferenceIsMaskedLikeTheGatewayWouldFlagIt` and
`nonLuhnDigitRunInsideAReferenceAndUuidsStayAsTheyAre`.

**How to run.** `mvn clean verify`. Free.

**Result.** Build after the fix: 168 unit / 193 IT, 0 failures. The re-run reached the model. E-28.

**Honest limits.** Cards split by dots or across UUID-like dash groups are not masked by reconcile-ai; the gateway still
refuses them. That is fail-safe, not a leak.

**Interview question.** *The failure was safe, so why fix it?* A safe refusal still blocks a legitimate investigation.
The gateway is the stricter control, so my masker should not be weaker than the thing behind it.

## F10 Golden path and EKA-down demo, Q4.2 and Q4.5

**What and why.** One run through every service shows the integrated flow, and one deliberate failure shows that it
degrades visibly.

**Design decision.** Local stack: Keycloak 8190, EKA edge 8080, ai-gateway 8280 (real provider, tenant ceiling 0.50
USD), reconcile-ai 8085 on the `mock-llm` profile, so the gateway is the only chat path.

**Tests.** `scripts/golden/up.sh`, `scripts/golden/golden-path.ps1` and `golden-path.ps1 -EkaDown` in the platform repo.
Paid, approved; provider key only in the git-ignored `.env`.

**Result (2026-11-18).** Demo 1: knowledge `OK` (3 procedures), gateway 200 `EXECUTED`, 0.00023460 USD,
`FEE_OR_CHARGE` 0.900, `L1_APPROVAL`, approved by a different person, `RESOLVED`, audit chain valid (13 events). Demo 2
(EKA stopped): job `SUCCEEDED`, knowledge `UNAVAILABLE`/`NETWORK`, 0.00018810 USD, same outcome, audit chain valid (20
events). E-28; `docs/proof/q4-platform-golden-path-2026-11-18.md`.

**Honest limits.** One run each, one laptop. No capacity, repeatability or provider-outage claim. Not run on a clean-stack CI.

**Interview question.** *What does the EKA-down demo show?* Fail-open is real: the degraded context is visible on the
job, the proposal still needs a different person to approve, and the audit chain is still valid.

## F11 Integrated evaluation, Q4.3

**What and why.** Does adding EKA and the gateway change decision quality? 28 labelled synthetic cases, `gpt-4o-mini` on
both sides, test hashing embeddings.

**Tests.** Baseline `OpenAiInvestigationEvalIT` (direct, no knowledge). Integrated `IntegratedPlatformEvalIT` (tag
`platform-live`; real EKA edge plus gateway). Paid, approved; needs the golden-path stack.

**Result (2026-11-18).**

| Metric | Baseline | Integrated |
|---|---|---|
| Root cause | 27/28 | 27/28 |
| Route | 24/28 (85.7%) | 19/28 (67.9%) |
| Citations valid / recall | 100% / 100% | 100% / 100% |
| Latency p50 / p95 | 2634 / 3555 ms | 2779 / 3825 ms |

All five route differences were cautious (four to manual review, one HRD-002 to L1 approval); none turned a wrong cause
into an approval. Gateway ledger for the day: 28 OK calls, 0.006053 USD. E-29.

**Honest limits.** n = 28, one run per column, synthetic cases, not a benchmark; a 5-case change is not significant. The
harness reports 0 cost for `ai-gateway` because it has no price; the ledger is the cost source.

**Interview question.** *Your integration made route accuracy worse. What do you do?* Report it, check the direction (it
was cautious), and isolate the cause with an ablation (F12) before changing anything.

## F12 Knowledge ablation, Q4.3

**What and why.** F11 could not say whether EKA or the gateway caused the route drop. Change one thing.

**Tests.** `IntegratedPlatformEvalIT` with `EVAL_KNOWLEDGE=off`: gateway route on, knowledge pre-step off. Paid,
approved.

**Result (2026-11-18).** Root cause 27/28; route 24/28 (85.7%), equal to the baseline; citations verified 96.0% (HRD-002,
a wrong cause present in all runs); recall 100%; p50 2853 ms, p95 4063 ms; 15951 in / 2969 out tokens. Ledger: 25 OK
rows, 0.00417405 USD, tokens equal the harness totals. The five changed cases return to the baseline route. Discordant
cases against the baseline split 4/1, exact two-sided p = 0.375. E-30. Total OpenAI spend that day about 0.0144 USD.

**Honest limits.** The mechanism (irrelevant fee procedures lowering confidence, because tenant-a's corpus holds only fee
procedures) is a working explanation, not measured. Three cases made no model call and that was not investigated. The
relevance-threshold fix is not built.

**Interview question.** *What would you ship?* Keep knowledge fail-open but include procedures only above a relevance
threshold or when the category matches, then re-measure with a new approved run.

## F13 RERO integration on fakes, Q3.2 and Q3.5

**What and why.** An optional Python service (RERO, see the `rero` repo) drafts a recommendation with a self-check.
reconcile-ai must give it read-only tools without giving it any path to approve or resolve.

**Design decision** (`docs/adr/ADR-Q3-rero-integration.md`). ES256 run token per job; separate `/internal/**` security
chain (non-GET is 405, a user JWT is 401); atomic tool budget by one SQL `UPDATE ... RETURNING` (V16); RERO mode off by
default (`RERO_ENABLED=false`); cited ids must be a subset of chunks served to this job; `CHALLENGE` maps to manual
review and `PASS` can never lower review.

**Tests.** `RunTokenIT` (15; wrong aud, iss, scope, job, tenant, state refused), `ReroInvestigationIT` (13),
`ReroToolBudgetConcurrencyIT` (30 parallel calls against budget 5), `InternalApiReadOnlyTest`, `ReroArchitectureTest`
(RERO classes cannot reach approval, resolution or routing code), `ReroDisabledByDefaultIT`, `ReroGovernanceIT`
(`MODE_NOT_GOVERNED` unless `RERO_MODEL_VIA_GATEWAY` is declared), consumer contract tests. A fake RERO
(`FakeReroServer`) calls the real internal API with the run token.

**How to run.** `mvn clean verify`. Free. Tag `rero-live` is reserved and excluded.

**Result.** 197 unit / 224 IT, 0 failures. 30 parallel calls vs budget 5: 5 accepted, 25 with 429. CI green: `ci` #59
(PR), #60 (main), commit `9479cdb`. E-31.

**Honest limits.** Fakes only. No real RERO-to-gateway run and no quality or keep/cut claim. The governance flag is a
declaration, not a proof.

**Interview questions.**

- *How do you stop a budget race?* One conditional SQL update, so the database decides; 30 threads against budget 5
  gave exactly 5.
- *Why can RERO never approve anything?* There is no write route, non-GET is 405, and an architecture test forbids the
  import path.

## F14 Q3.7 RERO keep/cut evaluation

Status: **done 2026-11-18, decision CUT (E-32).** Paid run (approved), both engines on gpt-4o-mini through the real ai-gateway, knowledge off for both, same day. C1 fail (HRD-002: RERO sent a wrong cause to L1 where the baseline chose manual review), C2 fail (root cause 26/28 vs baseline 28/28; McNemar p = 0.50, not significant), C3 fail (evidence recall 23/24 vs 24/24); C4 pass (cost 1.34x), C5 pass (p95 6522 vs 5460 ms), C6 pass (no budget overrun, no duplicate runs, no unknown outcomes). Spend 0.0145 USD from the gateway ledger. RERO stays disabled by default; the Q1/Q2 three-service platform is the final shape. Interview point: the rule was frozen before the code and the negative result is published as it stands. Full report: `reconcile-ai/docs/proof/q3-7-rero-keep-cut-2026-11-18.md`. The rule was frozen before Q3 code (`docs/adr/RERO_EVALUATION_RULE.md`, E-23).

## F15 Real gateway and per-tenant realm clients, PQ1.1.1 and PQ1.1.2

**What and why.** The fakes cannot prove that the real realm issues the right token or that the real gateway scopes its
ledger by tenant. Two free opt-in tests do, against the local spike stack (Keycloak 8190, ai-gateway 8280 on the stub
provider).

**Design decision.** No realm change: the platform realm already holds `svc-reconcile-tenant-a` and `-b`, each with a
hard-coded `tenant_id` claim. Secrets come from the environment only (`GATEWAY_CLIENT_SECRET`, `KC_SVC_TENANT_A_SECRET`,
`KC_SVC_TENANT_B_SECRET`).

**Tests.** `RealGatewayIT` (one job, ledger link) and `RealGatewayPerTenantIT` (tag `gateway`; one job per tenant, each
invocation read from the ledger with both tenants' tokens). Skipped when the variables are missing.

**How to run.** `bash scripts/spikes/up.sh` in the platform repo, export the secrets, then
`mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway-live" -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RealGatewayIT`
(or `RealGatewayPerTenantIT`).

**Result (2026-11-18).** Both passed. `RealGatewayIT`: 296 in / 9 out tokens, ledger 200 `OK` `EXECUTED`, cost 0; the job
ends `FAILED / MODEL_OUTPUT_NOT_VALID_JSON` by design (stub text). Per-tenant: A's invocation is 200 under A's token and
not 200 under B's, and the reverse. Register: E-20 (real gateway, stub provider) and E-34 (per-tenant clients). Proofs: `docs/proof/pq1-1-1-real-gateway-stub-2026-11-18.md`,
`docs/proof/pq1-1-2-per-tenant-realm-clients-2026-11-18.md`.

**Honest limits.** One run, one machine, not in CI. The cross-tenant check asserts "not 200", not the exact code. Refusal
paths stay on the fake gateway (`GatewayPerTenantCredentialsIT`).

**Interview question.** *How do you know the right client was used?* The gateway's own ledger, which is tenant-scoped by
the token, answers it: the row is visible only under the tenant's token.

## F16 Golden path and EKA-down in CI, PQ1.1.6

**What and why.** The two platform demos were local-only and paid. CI needs a key-free version that still exercises the
whole product path.

**Design decision.** `mock-llm` profile (no direct chat model), the schema-checked `FakeGatewayServer` as the only chat
path, per-tenant clients for gateway and knowledge, and either `FakeEkaServer` or an EKA address where nothing listens.
Real application and real Postgres; no key, no spend.

**Tests.** `PlatformGoldenPathIT`: knowledge `OK` with one `kb:` ref, one gateway call under the tenant's client, route
L1, approval by a different person, break `RESOLVED`, audit chain valid. `PlatformEkaDownIT`: connection refused, job
`SUCCEEDED`, knowledge `UNAVAILABLE`, no refs, same approval and valid audit chain. Both run in `mvn verify` and CI.

**How to run.** `mvn clean verify`, or `-Dit.test=Platform*IT`.

**Result (2026-11-18).** Both passed on the first run (2 tests, 0 failures). Register: E-35 (local pass; the CI link is added after the push).

**Honest limits.** Fakes, not the real EKA or gateway; they prove our wiring and failure handling, not the other
services. The paid local demos (F10) remain the proof of the real stack.

**Interview question.** *How do you test an outage without flakiness?* Point the client at an address nothing listens on;
the failure is deterministic and needs no container to stop.

## F17 Relevance filter re-measure, PQ1.2.4

**What and why.** F12 showed unfiltered knowledge cost 5 route cases. D1.1.1 added a relevance filter; this measures it.

**Tests.** `IntegratedPlatformEvalIT` (tag `platform-live`), three runs, `KNOWLEDGE_MIN_SCORE=0.60` (set from EKA cosine
probes before the runs: unrelated 0.43 to 0.52, generic query 0.67). Paid, approved, cap 0.10 USD.

**Result (2026-11-18).** Route 21/28 (75.0%) in all three runs; root cause 27/28; citations 100%. Baseline 24/28,
unfiltered 19/28. TIM-001 and HRD-002 recovered; MIS-001 to MIS-003 did not. 95% Wilson interval 56.7% to 87.3%. Ledger
spend 0.01450230 USD for 75 calls. Register: E-36. `docs/proof/pq1-2-4-relevance-filter-remeasure-2026-11-18.md`.

**Honest limits.** n = 28; the interval includes the baseline. The EKA corpus was reconstructed (three fee procedures).
The score floor cannot discriminate because the query is the same for every break; the category match does the work and
finds no category when the evidence uses other words. Per-case filter counts were not collected.

**Interview question.** *Did the fix work?* Partly: it recovered 2 of 5 cases and is not significant at n = 28. I report
that, and name the next step (wider category detection, non-fee procedures in the corpus) rather than claim a fix.

## F18 L1 cache in front of Redis, D1.2.1

**What and why.** Repeated investigations hit the Redis cache; each hit costs three Redis round trips. A small
Caffeine tier in the JVM answers repeats without Redis. Off by default (`CACHE_L1_ENABLED`, needs `CACHE_ENABLED`).

**Tests.** `TieredSemanticCacheIT` (real Redis 7 in Testcontainers): the app bean is tiered when on; an L1 hit after
`FLUSHALL` (so Redis cannot have served it, and the L2 miss counter does not move); other tenant, other evidence
snapshot and a suspect note all miss; an L2 hit is copied into L1; TTL with a fake ticker, capped at the L2 TTL; size
bound; a namespace bump misses. Plus a latency sample.

**Result (2026-11-18).** 8/8. L1 p50 159.5 us, p95 323.9 us; Redis exact hit p50 6185.8 us, p95 17497.3 us (500
lookups, one laptop). `docs/proof/d1-2-1-l1-cache-latency-2026-11-18.md`.

**Honest limits.** Redis runs in the Docker Desktop VM, which inflates L2 times; the ratio is the claim. L1 is per
replica: after a Redis flush a replica may serve an entry for up to the L1 TTL (5 minutes). It saves Redis calls, not
model calls.

**Interview question.** *How do you keep two cache tiers consistent?* Entries are immutable (exact prompt plus evidence
set), L2 has no delete path, and L1 follows the same end-of-life rules: namespace in the key, TTL never longer than L2.

## F19 Read-only MCP server, D1.2.2

**What and why.** MCP clients can read breaks, evidence lists and similar resolved cases with the same tenant and role
rules as the REST API. Off by default (`MCP_SERVER_ENABLED`). Design first: `docs/adr/ADR-mcp-read-only-server.md`.

**Tests.** `McpReadOnlyServerIT` (real app, real Postgres, real JWT validation, Spring AI MCP server, WebMVC,
stateless): no token 401; `SRE_ADMIN` 403; `Origin: http://evil.example` 403; `tools/list` is exactly the four read
tools; tenant A asking for tenant B's break id gets `NOT_FOUND` and B's break is not in A's list; each call adds one
`MCP_TOOL_CALLED` audit row for the caller's tenant, actor = JWT subject, with no break id or result text in the
details; the per-tenant rate limit refuses with a tool error and audits `RATE_LIMITED`, and another tenant is not
affected. `McpDisabledByDefaultIT`: no server or tool bean, `/mcp` is 404.

**Result (2026-11-18).** 6/6 and 1/1.

**Honest limits.** CVE-2026-35568 (mcp-core 0.18.3) is live when the server is on; it is mitigated, not fixed (ADR
section 3). The rate limit is per JVM. Results are plain text. Tested with raw JSON-RPC, not with a third-party MCP
client.

**Interview question.** *How does an MCP tool know the tenant?* From the JWT of the HTTP request that carries the call.
The stateless protocol runs the tool on that request thread; the session modes are refused at startup for that reason.

## F20 Break-specific knowledge query, D1.2.12

**What and why.** F17 showed the score floor cannot separate breaks because every break sent the same EKA query. The
query is now built from the break: category hints (from note and evidence), variance band and direction, intake source,
up to three evidence file names, the note. PII-masked, at most 400 characters, no amount or break reference. Default on
(`KNOWLEDGE_BREAK_SPECIFIC_QUERY=true`) because every existing test and the golden path stayed green; `false` restores
the generic query. The audit row records `queryKind`.

**Tests.** `BreakKnowledgeQueryTest` (fields, masking, cap, file limit, different breaks give different queries);
`BreakSpecificKnowledgeQueryIT` (the fake EKA receives the break-specific query; audit says `BREAK_SPECIFIC`);
`GenericKnowledgeQuerySwitchIT` (switch off sends the old query; audit says `GENERIC`).

**Result (2026-11-18).** 6/6 unit, 1/1 and 1/1 IT; the earlier knowledge ITs and the golden path stay green.

**Paid re-measure (2026-11-18, approved, cap 0.02 USD).** `IntegratedPlatformEvalIT`, two runs, same corpus and harness
as F17. `min-score` was re-probed free before any paid call: highest unrelated-query cosine 0.569, lowest per-break
cosine 0.657, midpoint rule gives 0.61. Route 23/28 (82.1%) and 22/28 (78.6%) against 21/28 (PQ1.2.4), 24/28 baseline,
19/28 unfiltered; Wilson 95% 64.4% to 92.1% and 60.5% to 89.8%. TIM-004 recovered in both runs and TIM-002 in one; MIS-001
to MIS-003 still lost. Per-case kept/dropped: 42 kept and 33 dropped per run, all drops from the category match. Ledger
spend 0.00943470 USD for 49 calls. `docs/proof/d1-2-12-break-query-remeasure-2026-11-18.md`.

**Honest limits.** No gain the sample can show (intervals contain the baseline; the run-to-run gap equals the gain). The
score floor dropped nothing because every per-break score is above 0.61. MIS cases give no category word, so the query
falls back to generic words and the fee procedures are kept. One run-1 job was refused by the gateway (`422
SENSITIVITY_BLOCKED`), not investigated.

**Interview question.** *Why not send the amount?* EKA holds procedures, not breaks. The size band and direction carry
the useful signal; the figure would only leak data to another service.

---

# Phase 1 to 5 features (added for PQ1.2.11)

The entries below cover the features built before the platform work (Foundation, Tier 2, production hardening,
evaluation, agents, alerts, Bedrock, AWS, supervisor agents, observability and cache proof). Rules for these entries:

- A test count is given only when it is printed in a log under `docs/proof/` or in a register row. Otherwise the entry
  lists the test classes without a count.
- "Deferred to the final phase" means the feature needs real AWS credentials, a real SMTP account or paid keys that were
  not available. The local or fake-service proof is listed, and the real run is not claimed.
- The 2026-11-10 and 2026-11-15 proof documents are one-off local runs, not CI runs.
- Suite-level numbers: E-01 (96 unit / 143 IT / 15 UI, CI run #33), E-16 (115 / 144 / 15, all 7 CI jobs green), E-33
  (UI 29/29), and the platform rows above for later counts.

## F21 Foundation: security, tenant isolation, breaks and evidence (Tier 0 to 1)

**What and why.** JWT resource server with a mandatory `tenant_id`, closed role set, 404 for another tenant's data,
Postgres Row-Level Security, exact money, evidence upload with hash dedup and the original file kept.

**Tests.** `SecurityAndTenantIsolationIT` (release-blocking), `RowLevelSecurityIT`, `BreakAndEvidenceIT`,
`EvidenceContentIT`, `RoleTest`, `MoneyAndVarianceTest`, `DemoLoginIT`, `KeycloakIT`, `VirtualThreadsTenantIsolationIT`,
`TenantScopeVirtualThreadTest`, `Day0SpikeIT`.

**Run.** `mvn clean verify` (Docker needed; free). One class: `mvn verify -Dit.test=SecurityAndTenantIsolationIT`.

**Expected.** 401 without a token, 403 for a wrong role, 404 for another tenant's id, a client-supplied tenant is
ignored; duplicate break reference 409; duplicate evidence returns 200 with one stored copy; an unscoped DB connection
sees no rows.

**Evidence.** E-01, E-02 (issuer and audience checks, `KeycloakIT`), E-13 (virtual threads keep tenant identity), E-16.
README sections "Features" and "Test suite".

**Limits.** Demo-key mode checks neither issuer nor audience (E-02). No external penetration test.

## F22 Investigation pipeline, Tier 2

**What and why.** Evidence ingestion (Tika, chunks, pgvector), hybrid retrieval (dense plus `tsvector`, fused with RRF),
cited proposals, deterministic policy gate, maker-checker approvals, hash-chained audit, SSE progress, recovery of
orphaned jobs.

**Tests.** `InvestigationFlowIT`, `GoldenPathIT`, `EvidenceIngestionIT`, `HybridRetrievalIT`, `RrfFusionTest`,
`CitationVerifierTest`, `PolicyGateServiceTest`, `InvestigationPromptBuilderTest`, `InvestigationRecoveryIT`,
`InvestigationStreamingIT`, `SseProgressBroadcasterTest`, `SseFanoutIT`, `AuditChainIT`, `AuditHashingTest`,
`ResolutionPathArchitectureTest`.

**Run.** `mvn clean verify`. Key-free: the default profile is `mock-llm`.

**Expected.** Evidence, then a cited proposal, then a route, then approval by a different person, then `RESOLVED`; an
injection note is quarantined; the audit chain verifies and the append-only trigger rejects updates; a reviewer
challenge always routes to manual review (E-04); only `ResolutionCommitService` resolves a break (E-03).

**Evidence.** E-01, E-03, E-04, E-16; the phase 2 logs `docs/proof/phase2a-unit-test.log`,
`docs/proof/phase2a-failsafe.log`, `docs/proof/phase2b-verify.log`, `docs/proof/phase2c-verify.log` (each ends in
BUILD SUCCESS; they are UTF-16 files, open with an editor that detects it).

**Limits.** The model proposes, Java decides; model quality is measured separately (F25, F26).

## F23 Production hardening: resilience, PII, outbox, digest, rate limit, API hygiene

**What and why.** Retry plus circuit breaker around the model, PII masking (identifiers, optional Presidio names),
transactional outbox to Kafka, daily digest, per-tenant rate limit on investigations, optional Swagger, idempotency
keys and `problem+json` errors.

**Tests.** `LlmResilienceTest`, `LlmResilienceIT`, `PiiMaskerTest`, `NerMaskingTest`, `PresidioNerIT`,
`InjectionScannerTest`, `KafkaOutboxIT`, `DailyDigestIT`, `TokenBucketRateLimiterTest`, `RateLimitIT`,
`SwaggerEnabledIT`, `SwaggerDisabledByDefaultIT`, `IdempotencyIT`, `ProblemJsonIT`.

**Run.** `mvn clean verify` (Kafka, Redis and Presidio run in Testcontainers).

**Expected.** Breaker opens and fails fast on an outage and the break goes to `FAILED_RETRYABLE`
(`docs/adr/ADR-llm-circuit-breaker.md`); IBAN and card masked before the call; one break despite Kafka redelivery,
malformed messages to the dead-letter topic; third request of a burst gets 429 with `Retry-After`; default config
exposes no Swagger paths.

**Evidence.** E-01, E-16; ADRs `ADR-llm-circuit-breaker.md`, `ADR-outbox-kafka.md`; README "Test suite" table.

**Limits.** The rate limit is per JVM unless `RATE_LIMIT_STORE=redis` (F6).

## F24 Audit anchoring (signed chain heads in S3 Object Lock)

**What and why.** Ed25519-signed chain heads written once to an Object Lock bucket, so a consistent rewrite of the
audit table is detectable.

**Tests.** `AuditAnchorIT` (real MinIO with Object Lock).

**Run.** `mvn verify -Dit.test=AuditAnchorIT`.

**Expected.** Anchor written once; the locked object cannot be deleted; a consistent chain rewrite is detected; only
`SRE_ADMIN` and the right tenant can verify; signatures cannot be forged.

**Evidence.** README features table (Audit row) and "Test suite" row for `AuditAnchorIT`; register E-54 (CI green for
MinIO and LocalStack). No separate proof file.

**Pending (real AWS).** Anchoring to real S3 (PQ1.2.8). MinIO and the LocalStack Object Lock check in CI
(`terraform-localstack` job) are the local proof.

## F25 Evaluation harness, Phase 4A

**What and why.** 28 labelled synthetic breaks through the real pipeline; root cause, route, citation and retrieval
numbers; the mock-llm run is a regression gate in the build.

**Tests.** `EvalCaseLoaderTest`, `InvestigationEvalIT`, `RetrievalEvalIT`, `EvalRunnerCitedFilesIT`.
Paid and opt-in: `OpenAiInvestigationEvalIT` (tag `openai`, needs `OPENAI_API_KEY`).

**Run.** Gate: `mvn clean verify`. Live: set `OPENAI_API_KEY`, then
`mvn verify "-Dfailsafe.excludedGroups=ollama,bedrock,gateway,gateway-live,eka,platform-live,rero-live" -Dit.test=OpenAiInvestigationEvalIT`.
Compare reports with `scripts/eval-compare.ps1`.

**Expected.** Mock gate: metrics at or above the floor in `thresholds-mock.json`, nothing routes to `AUTO_RESOLVE`.
Live single call (2026-11-09, `gpt-4o-mini`): root cause 96.4% (27/28), $0.00015 per case.

**Evidence.** E-10; `docs/proof/phase4-openai-eval.md`; `eval/human-scores.csv`.

**Limits.** Synthetic, author-written cases, n = 28, one run; not a benchmark. Live run is local, not in CI.

## F26 Investigation agent (4B) and supervisor agents (4G)

**What and why.** Optional `AGENT` mode (LangChain4j, four read-only audited tools, tenant bound from the job) and
`SUPERVISED` / `SUPERVISED_PLANNED` modes (analyst, risk checker, reviewer, one shared budget). Both off by default.

**Tests.** `AgentInvestigationIT`, `AgentSecurityIT`, `AgentAuditIT`, `AgentSearchQueryIT`,
`AgentDisabledByDefaultIT`, `SupervisedInvestigationIT`, `SupervisedDisabledByDefaultIT`, `ReconToolsTest`,
`ReviewerVerdictTest`, `AgentEvalIT`. Paid: `OpenAiAgentEvalIT`, `OpenAiModeComparisonIT`, `SupervisedEvalIT`.

**Run.** `mvn clean verify` for the free tests. Live comparison needs `OPENAI_API_KEY` (see F25).

**Expected (free).** A tool never accepts a tenant argument; denied and successful calls are audited; modes are off
unless configured; a reviewer challenge routes to manual review.
**Expected (live, E-10).** Agent: root cause 100% at about $0.0012 per case (about 8x single call), route accuracy
57.1% against 89.3%. Supervised on 40 cases: 7.9x and 12.3x cost against a 5x limit, so the rule written in advance
was not met; single call stays the default.

**Evidence.** E-04, E-10, `docs/proof/phase4-openai-eval.md`, `docs/proof/phase4g-supervised-eval.md`, raw reports in
`docs/proof/4g-live-run3-2026-11-13/` (the partial first attempt is `4g-live-run1-2026-11-13/`),
`docs/adr/ADR-supervisor-agents.md`.

**Limits.** One run, one model, synthetic cases. Live runs are local and paid, never in CI.

## F27 Alert delivery, Phase 4C

**What and why.** Prometheus alert rules routed by Alertmanager to e-mail (breaker open, LLM error rate, spend burn,
NER down, outbox, failed investigations, tool denials).

**Tests.** `docker/observability/alerts.test.yml` (`promtool test rules`, 14 named cases in the file; README text
mentions 14 and 16, the file is the source), CI job `alert rules + alertmanager config`, `ObservabilityIT` (series
names the rules query exist).

**Run.** `promtool test rules docker/observability/alerts.test.yml` (or the CI job). Outage drill: follow
`docs/proof/4c-outage-drill-2026-11-10.md`.

**Expected.** Rule tests pass; in the drill a real 503 outage and a closed NER port produced alerts that reached a
local Mailpit inbox (`docs/proof/4c-alert-email.txt`).

**Evidence.** `docs/proof/4c-outage-drill-2026-11-10.md`, `docs/proof/4c-alert-email.txt`, CI `ci` (alerts job), E-55.

**Deferred to the final phase.** Real SMTP delivery (PQ1.2.7, D1.2.11): needs real credentials in `alertmanager.yml`
(kept out of git). Only the Mailpit sink is proven.

## F28 Amazon Bedrock adapter, Phase 4D

**What and why.** `bedrock` chat profile beside `openai` and `ollama`; embeddings stay on OpenAI.

**Tests.** `BedrockAdapterIT` (parsing, usage and cost, output cap, throttle retry, no retry on bad input, breaker
opens), `BedrockFakeEvalIT` (28 cases through the adapter against a fake Converse endpoint). Live, tag `bedrock`:
`BedrockEvalIT`, `BedrockChatSmokeIT`.

**Run.** Fake: `mvn clean verify`. Live (deferred): AWS credentials and model access, `BEDROCK_LIVE=true`, then run the
two `bedrock` tests with `-Dfailsafe.excludedGroups=` set to exclude the other tags.

**Expected (fake).** Scores equal the mock-llm floor (adapter fidelity, not model quality).

**Evidence.** `docs/proof/4d-bedrock-fake-eval-2026-11-10.md`, `docs/proof/eval/report-bedrock-fake-2026-11-10.md`, E-56.

**Deferred to the final phase.** Live Bedrock runs and the OpenAI versus Bedrock comparison (PQ1.2.6). Cost and quality
on Bedrock are not claimed.

## F29 AWS deployment with Terraform, Phase 4E

**What and why.** Terraform for ECS Fargate, RDS pgvector, S3 Object Lock, Secrets Manager, GitHub OIDC, budget;
deploy, record, destroy.

**Tests.** `infra/terraform/**/tests/*.tftest.hcl` (38 `run` blocks across 8 files, mock AWS provider), CI jobs
`terraform fmt, validate, test, tflint, checkov` and `terraform apply + destroy against LocalStack` (apply, no drift,
Object Lock refuses a delete, destroy). `deploy-aws.yml` is the real-account workflow.

**Run.** `terraform -chdir=infra/terraform/modules/network test` (and each module); LocalStack apply:
`docs/proof/4e-localstack-apply-2026-11-10.md`.

**Expected.** All module tests pass; the LocalStack apply and destroy leave no resources and no drift.

**Evidence.** `docs/proof/4e-localstack-apply-2026-11-10.md`, CI `ci` job `terraform-localstack` (`ci` #56 per the
status tracker), `infra/README.md`, E-57. Account setup and cost steps for the real apply:
`platform/docs/deployment/AWS_SETUP_BILLING_DEPLOYMENT_GUIDE.md` (platform repo, written 2026-11-19).

**Deferred to the final phase.** Real apply, record and destroy in an AWS account (Q4.6, D1.2.8, PQ1.2.8); needs
credentials and cost approval. LocalStack proves the plan shape, not real-AWS behaviour or cost.

## F30 Observability dashboard and semantic cache savings, Phase 5C and 5D

**What and why.** One real run fills the Grafana dashboard, Prometheus series and Jaeger traces. The Redis semantic
cache serves repeat investigations without a model call.

**Tests.** `ObservabilityIT`, `SemanticCacheIT`, `SemanticCacheMathTest`, `TieredSemanticCacheIT` (F18).

**Run.** Mechanism: `mvn clean verify`. Dashboard run: `docker compose -f docker/docker-compose.yml --profile
observability up -d`, start the app with `TRACING_ENABLED=true`, run `scripts/demo-golden-path.ps1`. Cache run:
`--profile cache` and `CACHE_ENABLED=true`. Both runs use real OpenAI embeddings (`OPENAI_API_KEY`, under one cent).

**Expected.** Dashboard panels and traces fill; metrics carry no tenant label by design. Cache: 3 repeats served without
a model call (2 exact, 1 semantic), 3 misses called the model, route unchanged; added evidence forces a fresh call.

**Evidence.** E-14, E-15, `docs/proof/5c-observability-dashboard-2026-11-15.md`,
`docs/proof/5d-semantic-cache-savings-2026-11-15.md`, `docs/adr/ADR-semantic-cache.md`.

**Limits.** The dashboard screenshot named in E-14 (`docs/media/5c-grafana-dashboard-2026-11-15.png`) is not in the
repo: `docs/media/` is empty. Savings are one local measurement with list-price estimates, not a production hit rate.

## F31 Console UI and Kubernetes manifests

**Tests.** UI: `ui/src/app/**/*.spec.ts` (Karma). Kubernetes: CI step `kubeconform` on `k8s/base`,
`k8s/overlays/kind`, `k8s/overlays/keda`.

**Run.** `cd ui && npx ng test --watch=false --browsers=ChromeHeadless`; render and validate with `kubectl kustomize`
and kubeconform as in `k8s/README.md`.

**Expected.** UI 29 of 29 passed (E-33). Manifests pass strict schema validation.

**Evidence.** E-33, E-21, E-44. KEDA was run once on a kind cluster with a stub app and a stub Prometheus
(`docs/proof/pq1-2-1-keda-kind-2026-11-19.md`, F40); the manifests and HPA on kind before that have no proof file.
Browser end-to-end runs are not tested. Real-image KEDA scaling and the CPU trigger were run on 2026-11-19 (RA-5, E-65,
`k8s/overlays/keda-kind-real/`, `docs/proof/ra-5-keda-kind-real-2026-11-19.md`): embeddings stubbed, mock chat, one node.

---

# Changes of the last seven days (2026-11-12 to 2026-11-19), added 2026-11-19

F32 to F35 cover the latest changes (RA-3, INT-4, license and headers, Trivy image fix). F36 to F43 fill the gaps for the
rest of the week that F1 to F31 did not describe as separate features. Everything else from the week already has its own
entry: knowledge pre-step and relevance filter (F1, F17), gateway governance (F4 to F9), RERO (F13, F14, F43),
integrated evaluation (F11, F12), L1 cache, MCP and break query (F18 to F20), supervisor agents (F26), alerts, Bedrock fake,
Terraform and audit anchoring (F24, F27 to F29), dashboard and cache savings (F30).

Rules: numbers come from the cited proof documents, the CI log of the named run, or a report file on this machine.
"Local; CI pending push" means the work is in the working tree and no CI run exists for it yet. Nothing here claims an
accuracy gain, a capacity figure or real-AWS behaviour that was not measured. Counts of `@Test` methods taken from the
source are marked "in source"; they are not CI results.

## Summary table

| Feature | What it does | How it was tested (test class / script / proof doc) | Result (actual numbers, CI run id or "local; CI pending push") | Explanation | Trade-offs and alternatives considered | Status / pending |
|---|---|---|---|---|---|---|
| RA-3 missing-entry phrasing (F32) | Adds four phrases to the `MISSING_ENTRY` term list of the relevance filter so unrelated fee procedures are dropped for such breaks | `KnowledgeRelevanceFilterTest`, `BreakKnowledgeQueryTest`; `docs/proof/ra-3-missing-entry-terms-2026-11-19.md` | 10/10 and 6/6; unit suite 218, 0 failures; local; CI pending push | MIS-001 to MIS-003 evidence never used a listed word | Wider list may over-match | Accuracy effect not measured (E-58) |
| INT-4 concurrency (F33) | 10 to 25 parallel investigations through the real gateway (stub) and EKA | `scripts/int4/*`, `compose/int4/*` (platform repo); `docs/proof/int-4-concurrency-2026-11-19.md` | No crash, no stuck job, 95 proposals all `MANUAL_REVIEW`, 0 leak hits; local; CI pending push | Shows each limit degrades safely | Stub model, one laptop | Local pass (E-63); larger runs to be confirmed |
| License, AI use policy, headers (F34) | View-only `LICENSE`, `AI_USE_POLICY.md`, header and canary on 384 sources | CI run 44499744262, UI specs, unit tests | 7 of 7 jobs; 217 unit / 268 IT; UI 29/29 | Rights explicit, copies findable | No legal review | PR green; `main` run to be confirmed |
| Trivy pebble fix (F35) | Removes unused `/usr/bin/pebble` from the image | CI job `k8s manifests + container image` | CI run 44499744262 success | Dead binary with a HIGH CVE | `.trivyignore` rejected | PR green; `main` run to be confirmed |
| Virtual threads (F36) | Request handling on Java 21 virtual threads, bounded investigation pool kept | `VirtualThreadsTenantIsolationIT`, `TenantScopeVirtualThreadTest`; ADR | 120 interleaved requests, no cross-tenant row, all on virtual threads (E-13); CI at the time to be confirmed | I/O-bound service | Throughput not measured | Built (E-13) |
| Gateway adapter and refusal mapping (F37) | Model call through ai-gateway behind `GATEWAY_ENABLED` | `HttpGatewayChatAdapterTest`, `IdempotencyKeysTest`, `GatewayConfigTest`, `GatewayFailureTest` | CI baseline 115 unit / 144 IT at `887ec9f` (E-16, run 43714292363) | Governed path, no double billing | Off by default | Built (E-17) |
| Latest-job endpoint, Idempotency-Key, problem+json (F38) | Latest job per break; optional idempotent POSTs; RFC 7807 errors | `LatestInvestigationIT`, `IdempotencyIT`, `ProblemJsonIT` | 5, 10 and 6 `@Test` in source; CI run 44293523261 green | Safe retries and uniform errors | Key scope and 24 h store | CI green (counts per class to be confirmed) |
| Trivy gate (F39) | Image scan fails the build on HIGH or CRITICAL with a fix | CI job image, `.trivyignore`, `pom.xml` overrides | First gated run 32 findings: 26 cleared, 4 accepted; run 44293523261 green (E-40) | Supply-chain gate | 4 accepted with expiry | CI green |
| KEDA overlay on kind (F40) | Overlay run on a real cluster with stubs | `docs/proof/pq1-2-1-keda-kind-2026-11-19.md` | ScaledObject Ready; 2 to 4 to 6 on stub values; namespace bug fixed; CI run 44423686771 green | Mechanics, not real load | Stubs | Local with stubs (E-44) |
| Similar-breaks tool contract (F41) | Exactly three fields, approved and same tenant only | `SimilarBreaksToolIT` | 1/1; CI run 44423686771 green (E-46) | TDD item closed | Plain contract check | CI green |
| Repo hygiene (F42) | CI minutes saving, guide and evidence map, portfolio page | `.github/workflows/ci.yml` diff; docs | See F42 | Limited Actions quota | Nightly scan paused | Done; portfolio page not tested |
| RERO (F43) | Optional sibling service, cut under the frozen rule, off by default | RERO repo `pytest`; F13, F14 | 143 passed (local run 2026-11-19, 70 s); keep/cut: CUT | Rule fixed before the run | Kept as code, not default | Cut (E-32) |

## F32 RA-3 missing-entry phrasing in the relevance filter

| Check | Setup | Result |
|---|---|---|
| Cause found without a paid call | Read `KnowledgeRelevanceFilter` and the D1.2.12 per-case counts (`docs/proof/d1-2-12-break-query-remeasure-2026-11-18.md`) | MIS-001 to MIS-003 evidence says "No credit for INV-30402 appears anywhere", "no matching credit exists", "has no counterpart on the bank side"; none of the old `MISSING_ENTRY` words (`missing`, `not recorded`, `not posted`, ...) occurs, so no category was found, the query fell back to generic words and the filter kept all 3 fee procedures (kept 3 / dropped 0) |
| Change | `KnowledgeRelevanceFilter.TERMS` for `MISSING_ENTRY` | Added `no matching`, `no counterpart`, `no credit`, `no debit`. Nothing else changed: min-score, category rule, defaults, auto-resolve off |
| New unit test | `missingEntryPhrasingWithoutTheWordMissingIsRecognised`: the three evidence phrasings | Each yields `MISSING_ENTRY` as a candidate category; a fee procedure is dropped and the missing-entry procedure (`kb:2`) is kept |
| Existing tests | `KnowledgeRelevanceFilterTest`, `BreakKnowledgeQueryTest` | 10/10 and 6/6 (per the proof document) |
| Unit suite | Surefire reports on this machine, 2026-11-19 | 218 tests, 0 failures, 0 errors, 0 skipped |
| Side-effect check (free) | The phrases searched in the 28 eval evidence files and the hard set | Only `mis-001..003` and `hrd-006` contain them; no other evaluated case changes category set |
| CI | `ci` | Local; CI pending push. The last CI run on this branch (44499744262, before this change) shows 217 unit tests, consistent with 218 including the new test |

**Why it was built this way.** After D1.2.12 the filter still lost the three missing-entry cases for a plain reason: it
matches evidence text against short, specific term lists, and the evidence for those cases used another wording of the
same idea. The smallest honest fix is to extend that list, keep it short and specific, and leave every threshold alone, so
the change can be reviewed in one line and reverted in one line. The filter stays conservative: when no category is found
it keeps everything, which is why a missed phrase costs accuracy, not safety. The test uses the exact phrasing from the
evidence so a regression shows up immediately.

**Trade-offs and alternatives.**

- A broader list can match cases it should not (for example "no credit" inside an unrelated sentence). The free check
  found only the four expected cases among 28 plus the hard set; real data may differ.
- Alternative: let the model pick the category. Rejected: it adds a model call and makes the filter non-deterministic.
- Alternative: lower the score floor or drop the category rule. Rejected: F20 showed the floor is inert, and dropping the
  category rule removes the filter's only discriminating signal.
- Alternative: stop filtering for missing-entry breaks. Rejected: the 28-case baseline routes these correctly only without
  fee procedures, so the filter is still wanted.

**Not covered / limits of this evidence.** No model was called. The expected effect (MIS-001 to MIS-003 get 0 kept / 3
dropped fee procedures, i.e. the no-knowledge prompt that routed them correctly in the baseline) is a prediction from the
filter logic, not a measurement. With n = 28 and 3 cases affected, a gain could be told apart from the baseline only as
"back to baseline", not as an improvement over it.

**Pending.** Paid 28-case re-measure (the D1.2.12 reproduce command, expected cost about 0.005 USD per run; needs the
provider key and the owner's approval). Until it runs, no accuracy figure is claimed for RA-3 (E-58).

## F33 INT-4 concurrent investigations through the gateway and EKA

All figures: stub model, one laptop, one run per scenario, throwaway stack; behaviour and rough latency, not a capacity
claim. Source: `docs/proof/int-4-concurrency-2026-11-19.md`, raw results in `docs/proof/int-4-raw/`.

| Check | Setup | Result |
|---|---|---|
| A: N = 10, default pool | tenant-a, 10 starts released together, EKA documents seeded | 10 x 202, 10/10 `SUCCEEDED`, knowledge `OK` x 10; e2e p50 3398 ms, p95 5188 ms |
| B: N = 25, default config | tenant-b | 20 x 202 and 5 x 429 `Retry-After: 3` from reconcile-ai's own start limiter; the 5 breaks stayed `READY_TO_INVESTIGATE`; 20/20 accepted jobs `SUCCEEDED`; p50 3724 ms, p95 5940 ms |
| C: N = 25, wide pool (25 threads, start limiter 100), fail-open | tenant-a | 25/25 `SUCCEEDED`; p50 6512 ms, p95 7662 ms; 2 EKA lookups `UNAVAILABLE / RATE_LIMITED` and the jobs continued; 2 gateway 429s retried once under the same idempotency key and both succeeded |
| D: N = 25, wide pool, fail-closed | tenant-b, `KNOWLEDGE_FAIL_CLOSED=true` | 22 `SUCCEEDED`, 3 `FAILED / KNOWLEDGE_UNAVAILABLE` (2 rate limited, 1 timeout) with no model call; re-run of the 3: 3/3 `SUCCEEDED` |
| E: EKA core paused, fail-open | 10 starts | 10/10 lookups `UNAVAILABLE / TIMEOUT` at the 5 s limit, all 10 jobs still `SUCCEEDED` to manual review; breaker opened |
| E2: same app, 5 more | right after E | 5/5 `UNAVAILABLE / CIRCUIT_OPEN`, no request sent, jobs about 0.6 s |
| Direct bursts (not through reconcile-ai) | 40 and 60 parallel calls | EKA edge: 40 gives 21 x 200 / 19 x 429, 60 gives 21 / 39, `Retry-After: 1`; gateway: 40 gives 11 x 200 / 29 x 429 `TENANT_CONCURRENCY_LIMIT`, 60 gives 42 / 18 `RATE_LIMITED` |
| Safety invariants | Runs A to E2 | All 95 proposals routed `MANUAL_REVIEW`; no break `RESOLVED` (auto-resolve off); audit chains valid (tenant-a 389 events, tenant-b 292) |
| Leak scan | `scripts/int4/leak.py` over reconcile-ai logs and the gateway, EKA edge, core, worker and Keycloak container logs | 0 hits for JWT shape, Bearer value, analyst note, stub reply, evidence text and the four secret values |
| CI | needs Docker and three sibling repos | Local; CI pending push |

**Why it was built this way.** A single investigation working says little about behaviour when many arrive at once, and the
platform now has four places that can say "no": reconcile-ai's start limiter, the EKA per-user limiter, the gateway's
tenant limits and the model itself. The run was designed to reach each limit on purpose and then check one thing: does the
system end in a defined, safe state. The stub model keeps it free and repeatable; the analyst note carries a directive so
the stub returns a valid proposal and the whole pipeline (policy gate, route, break status) is exercised. The leak scan was
added because overload paths are where payloads end up in error logs.

**Trade-offs and alternatives.**

- Fail-open (default) keeps investigations flowing but 8 percent (C) and 12 percent (D) ran without procedure context under
  the burst; this is visible per job and in the `reconcile.knowledge.outcome` metric and never changes the route.
  Fail-closed gives complete context or a retryable failure, at the cost of re-runs.
- reconcile-ai did not retry a rate-limited EKA lookup in these runs (knowledge is advisory). Alternative: retry on
  `Retry-After`. See Pending: that change is in progress.
- The default pool (2 to 4 threads) keeps reconcile-ai below the EKA limit by itself (no EKA or gateway 429 in A and B);
  widening the pool needs the EKA edge limit (or a shared Redis limiter, ADR-025 in EKA) raised too.
- Alternative: load-test with a real provider. Rejected for this run: cost, and provider latency would hide the limiter
  behaviour being tested.

**Not covered / limits of this evidence.** One laptop, one run per scenario, stub model (about 0.4 to 0.9 s per call) and
an in-process embedding model on the EKA core, with fake embeddings for reconcile-ai's own ingestion. Not covered: more
than 25 parallel, more than one replica of any service, a second tenant loading the same EKA user, streaming, agent modes.
The leak check read the EKA edge log as a 1000-line Docker tail and did not scan the gateway ledger rows or database
contents (`PayloadLeakageIT` in ai-gateway covers the ledger). The first harness version had a fake-embeddings server bug
(chunked bodies, keep-alive); it was fixed and no table row includes it. No product bug was found, so no product code was
changed by this run.

**Pending (in progress, not recorded as done at the time of writing).** The proof document does not yet show: a 429 retry
that honours `Retry-After`, N = 50 and N = 100, two tenants loading the same EKA user, two replicas, or real gpt-4o-mini
latency. The working tree on 2026-11-19 contains uncommitted code and an unrecorded test for a rate-limit retry in the EKA
knowledge client (`EkaKnowledgeClient`, `EkaKnowledgeClientRetryTest`, `rate-limit-retries` settings); its results are
to be confirmed and are not claimed here. Ledger and database leak scanning and an untruncated EKA edge log are also open.

## F34 View-only license, AI use policy and source headers

| Check | Setup | Result |
|---|---|---|
| License replaced | `LICENSE` (commit `eb2362b`, PR #25) | Apache-2.0 text replaced by a view-only proprietary license: viewing on GitHub for evaluation is allowed; copying, cloning, forking outside GitHub's own terms, modification, redistribution, product use and AI use are prohibited without written permission; third-party libraries keep their own licenses |
| AI use policy | `AI_USE_POLICY.md` | New file: bans training, AI-based analysis or reproduction, inclusion in datasets or indexes, and scraping; states that it does not restrict the owner; names the canary marker |
| NOTICE and README | `NOTICE`, README license section and notice box | NOTICE adjusted in the same commit; README says "view-only license" and links both files |
| Headers and canary | Commit `058d279` | 3 comment lines (copyright, `SPDX-License-Identifier: LicenseRef-Proprietary`, canary) added to 384 source files (356 Java, 27 TypeScript, 1 Python); the commit also adds 4 lines to `AI_USE_POLICY.md` and 2 to the README; 386 files, 1158 insertions; `git grep` finds the canary in 386 files today |
| Does the build still pass | `ci` run 44499744262 on `bca99f6` (PR `chore/ai-use-policy`, includes `058d279`) | 7 of 7 jobs success; `mvn clean verify` 217 unit / 268 IT, 0 failures; UI Karma 29 of 29 |
| Local re-check after the headers | Unit tests and UI specs (E-58) | Unit suite passes; UI Karma ChromeHeadless 29/29 and `ng build` OK |
| License PR first run | `ci` run 44445726014 on `7e2d3ed` | Failed: `mvn clean verify` stopped with a Maven Central HTTP 429 while resolving a plugin (infrastructure, not a test). The PR later passed (44499744262). Main push of PR #25: run 44446274178, success |

**Why it was built this way.** A portfolio repository has to be readable by reviewers, but the owner does not want it reused
or fed to models. Apache-2.0 allowed both, so it was replaced. A separate AI use policy states the AI terms in one place,
and the license points to it. The canary string and a short header in every source file make a copy findable and make the
terms visible in each file, including when a file is read alone. Headers are comments only, so no behaviour changes, and
the full build and UI specs were re-run to prove that.

**Trade-offs and alternatives.**

- Alternative: keep Apache-2.0 and rely on the README notice. Rejected: it grants the rights the owner wants to withhold.
- Alternative: a source-available license that allows non-commercial use. Rejected for now: any copy right makes the AI
  restriction harder to hold; view-only is the strictest option.
- A header on every file is noisy and is removed by anyone who copies the file; the canary is a detection aid, not
  protection. A license cannot technically stop a crawler.
- Previously published Apache-2.0 versions stay under that license as far as the law requires (LICENSE section 5).

**Not covered / limits of this evidence.** The legal text has not been reviewed by a lawyer. The canary check is a text
search, not proof about copies elsewhere. Header presence is a count (384) from the commit, not a CI check; no test fails
when a new file lacks a header.

**Pending.** Merge of the header and pebble commits (`058d279`, `bca99f6`) into `main` and the CI run on `main`: to be
confirmed (the last `main` run found is 44446274178 on `eb2362b`).

## F35 Trivy image fix: remove the unused pebble binary

| Check | Setup | Result |
|---|---|---|
| Finding | Trivy image scan (fails on HIGH or CRITICAL with a fix) | HIGH CVE-2026-97054 in the Go standard library (v1.27.3) of `/usr/bin/pebble`, a Go service manager shipped in the `eclipse-temurin:21-jre` base image; fixed in Go 1.27.4 (as recorded in the Dockerfile comment). The CI log of the run that first reported it was not found, to be confirmed |
| Fix | `Dockerfile`, commit `bca99f6` | `RUN rm -f /usr/bin/pebble` after the user is created; the app starts `java` directly and never runs pebble |
| CI | `ci` run 44499744262 (PR, head `bca99f6`), job `k8s manifests + container image` | Success; the log shows the step `[stage-1 3/5] RUN rm -f /usr/bin/pebble`, and the Trivy gate passed with the four accepted findings in `.trivyignore` (expiry 2027-02-28) |
| Other jobs in the run | same run | All 7 jobs success (`mvn clean verify`, UI, SBOM, alert rules, Terraform checks, LocalStack apply and destroy, image) |

**Why it was built this way.** The gate fails on a HIGH finding with a fix, and the vulnerable code is a binary the product
never runs. Removing it is the smallest change that makes the image smaller and cleaner, and the reason is written next to
the line so the next reader knows when it can go (when the base image is rebuilt with a patched Go).

**Trade-offs and alternatives.**

- Alternative: add CVE-2026-97054 to `.trivyignore`. Rejected: it would accept a risk instead of removing it, and the file
  is reserved for findings with no fix inside the locked baseline.
- Alternative: wait for a base image rebuild. Rejected: the gate would stay red meanwhile.
- Alternative: a distroless or Alpine base. Not done: a larger change with its own scan results; not evaluated.
- Risk: if a future base image needs pebble (for example for another start script), start-up would fail, not the build.

**Not covered / limits of this evidence.** CI shows the gate passed on the PR branch. It does not show a Trivy report that
lists the CVE before the fix, and it does not show the scan result on `main`.

**Pending.** CI run on `main` after merge (to be confirmed).

## F36 Java 21 virtual threads for request handling (5A)

| Check | Setup | Result |
|---|---|---|
| Isolation under virtual threads | `VirtualThreadsTenantIsolationIT` (2 tests in source), real Postgres | 120 interleaved requests never saw another tenant's rows and every request ran on a virtual thread (E-13, README) |
| Thread-local hygiene | `TenantScopeVirtualThreadTest` | `TenantScope` is per thread and never inherited |
| Back-pressure kept | `InvestigationExecutorConfig` (core 2, max 4, queue 50) | The investigation pool stays a bounded platform pool, so a full queue still returns 503 |
| CI | commit `d663018` (PR #10) | CI run id for that commit: to be confirmed |

**Why it was built this way.** Almost every request waits on I/O (Postgres, model provider, EKA, gateway). A virtual thread
parks cheaply, and one Spring Boot flag moves Tomcat, `@Scheduled` and the default task executor, with no production code
change. The one real risk is tenant identity on a reused thread, so it was proven by test, release-blocking.

**Trade-offs and alternatives.**

- WebFlux was rejected by project rule (Spring MVC with `SseEmitter`).
- A virtual-thread investigation executor was rejected: no queue means no 503 back-pressure and unbounded calls into a
  rate-limited provider.
- Pinning risks (synchronized blocks around blocking calls) are not measured here.

**Not covered / limits of this evidence.** No throughput or latency claim; the proof is isolation, not speed.

**Pending.** Nothing planned; a load comparison would need its own run.

## F37 Gateway adapter, idempotency key and refusal mapping (Q2.1, Q2.2)

| Check | Setup | Result |
|---|---|---|
| Request and replies match the contracts | `HttpGatewayChatAdapterTest` against a fake gateway; replies checked on `chat-response.v1.json` and `error.v1.json` | Passes in the default build; per-class count to be confirmed |
| Idempotency key | `IdempotencyKeysTest`: `recon:<jobId>:<step>:<attempt>` | Same key for a re-claimed job and a transport retry |
| Retry rule | `GatewayFailureTest`, `GatewayConfigTest` | One retry only on not-sent codes; none on an unknown outcome or budget block; refusal codes map to `GATEWAY_<code>`; unknown-outcome and policy-blocked sets are disjoint |
| Suite at the time | E-16 (commit `887ec9f`, CI run 43714292363) | 115 unit / 144 IT / 15 UI, all 7 jobs green |

**Why it was built this way.** Behind `GATEWAY_ENABLED` (default off) the model call can use the platform's governed path:
budget, risk, sensitivity and a ledger. A fixed key per job step lets the gateway replay instead of billing twice. Typed
failure reasons let an operator tell a budget block from a policy refusal.

**Trade-offs and alternatives.**

- The local advisor chain and breaker are bypassed on this route; identifier masking still runs before the request leaves.
- Alternative: always retry. Rejected: a retry after an unknown outcome can double-bill.

**Not covered / limits of this evidence.** Fake gateway for the CI tests; real-gateway runs are F8, F15 and F11.

**Pending.** None for the adapter. Follow-up governance is F4 to F7.

## F38 Latest-job endpoint, Idempotency-Key and problem+json (D1.1.2, D1.1.4)

| Check | Setup | Result |
|---|---|---|
| Latest job for a break | `LatestInvestigationIT` (5 tests in source) | `GET /api/v1/breaks/{breakId}/investigations/latest` returns the newest job; 404 `JOB_NOT_FOUND` for no job or another tenant |
| Idempotent POSTs | `IdempotencyIT` (10 tests in source), Flyway V17, Row-Level Security | Same key and request replays status, body and `Location` with `Idempotency-Replayed: true`; same key, different request 422 `IDEMPOTENCY_KEY_REUSED`; running duplicate waits up to 10 s then 409; non-2xx not stored |
| Error shape | `ProblemJsonIT` (6 tests in source) | RFC 7807 `application/problem+json` with the old `code`, `message`, `timestamp` kept; 401 and 403 keep `WWW-Authenticate` |
| CI | commit `886cc9e`, `ci` run 44293523261 | All 7 jobs green; per-class counts in CI to be confirmed |

**Why it was built this way.** Clients retry, and a retry must not create a second break, evidence row or job. The header is
optional so existing callers are unchanged. A uniform error body lets consumers branch on a stable `type` and `code`.

**Trade-offs and alternatives.**

- Keys are per tenant, user and endpoint, kept 24 h, purged hourly: bounded storage, but a key reused after a day is new.
- Alternative: dedupe on content only. Rejected for investigations, which are intentionally repeatable.

**Not covered / limits of this evidence.** Single instance in tests; multi-replica races rely on the database constraint,
not a measured run. The console's use of the latest-job endpoint is covered by a UI spec (`break-detail.page.spec.ts`).

**Pending.** Nothing.

## F39 Trivy gate that fails the build (D1.1.3)

| Check | Setup | Result |
|---|---|---|
| First gated run | CI image job, `ignore-unfixed`, HIGH and CRITICAL | 32 findings; run 44282959795 (#63) failed |
| Fixes | `pom.xml` SECURITY OVERRIDES | 26 findings cleared by patch-level overrides (Tomcat, Jackson, Netty, Postgres driver, httpcore5 and transitive libraries) |
| Accepted | `.trivyignore` | 4 findings with no fix inside the Spring Boot 3.5 and Spring AI 1.1 lock (3 Spring Framework CVEs fixed only in 7.x, one `mcp-core` CVE fixed only in 1.0), each with justification and expiry 2027-02-28 |
| Result | PR run 44284685956 (#64), main run 44293523261 (#66), E-40 | All jobs green |

**Why it was built this way.** A scan that only reports is ignored. Failing on fixable HIGH and CRITICAL findings makes a
new vulnerable dependency visible in the pull request. Unfixable findings are accepted in writing with an owner and expiry
so they cannot be forgotten.

**Trade-offs and alternatives.**

- `ignore-unfixed` hides findings with no fix; alternative: fail on everything, rejected as permanently red.
- Alternative: upgrade to Boot 4 for the 3 Spring CVEs, rejected as out of scope and version-locked.

**Not covered / limits of this evidence.** The 4 accepted CVEs are a documented exception, not zero findings. The MCP CVE
is live when the MCP server is switched on (F19).

**Pending.** Review before 2027-02-28. The later pebble finding is F35.

## F40 KEDA overlay run on a kind cluster (PQ1.2.1)

| Check | Setup | Result |
|---|---|---|
| ScaledObject ready | kind v0.25.0 (Kubernetes v1.31.2), KEDA 2.21.1, metrics-server v0.7.2, stub app, stub Prometheus | `Ready=True`, 2 to 6 replicas, triggers `prometheus` and `cpu`; base HPA removed by the overlay |
| Bug found | first `kubectl apply -k` | ScaledObject had no namespace and the admission webhook refused it; fixed by `namespace: reconcile` in `k8s/overlays/keda/kustomization.yaml` (kubeconform in CI could not catch this) |
| Scale up | stub value 12, then 30 | 2 to 4 within 15 s; 6 of 6 at about +75 s (capped at 6) |
| Metric outage | Prometheus stub stopped at 5 of 5 | Replicas held at 5, `Ready=False TriggerError`, scaling resumed when it returned (about 1 minute outage tested) |
| Scale down | 300 s stabilisation window | First drop about 6 minutes after the change, then one pod per 60 s; not run down to 2 |
| CI | commit `5b4432f`, `ci` run 44423686771 | Green (E-44) |

**Why it was built this way.** Investigations wait on the model, so CPU is a weak load signal; queued and active
investigations are the real one. The run was to catch what a schema check cannot: admission, wiring and scaling mechanics.

**Trade-offs and alternatives.**

- A stub app and Prometheus make the run fast and repeatable but prove nothing about the real gauges.
- Alternative: CPU-only HPA (the base). Kept as the default; KEDA is an overlay.

**Not covered / limits of this evidence.** Real image, real
`reconcile_investigations_executor_*` metrics and the label in the query, the CPU trigger under load, scale-down to the
minimum, long outages.

**Follow-up done (RA-5, 2026-11-19, E-65).** `k8s/overlays/keda-kind-real/`: real image, real Prometheus v2.55.1 with the
`namespace` label, KEDA 2.21.1, unmodified ScaledObject. Back-log load: desired 2 to 4 in about 11 s while CPU read 2%,
6 desired within a minute, 6 ready after about 5 min, 248 requests refused 503 meanwhile, all 788 accepted jobs SUCCEEDED, 0 restarts.
CPU-only load (Prometheus sum 0): 2 to 6. Scale-in to the minimum 2 at one pod per minute after the 300 s window, seen three times.
Finding: JVM start-up CPU (about 1 core per pod for a minute) makes a fresh install scale out by itself. Stubs: embeddings,
chat (mock), login; rate limit off; one node, one run per phase. Proof: `docs/proof/ra-5-keda-kind-real-2026-11-19.md`.

## F41 Similar-breaks tool contract (PQ1.2.9)

| Check | Setup | Result |
|---|---|---|
| Exact fields | `SimilarBreaksToolIT` (1 test), real Postgres, schema `internal-similar.v1` | Each item has exactly `breakRef`, `rootCauseCategory`, `resolvedAt`; no explanation, amount, proposal id or tenant |
| Scope | rows seeded for tenant A (approved, rejected) and tenant B | Only approved proposals on resolved breaks of the token's tenant; never the job's own break; at most 5 items |
| CI | commit `5b4432f`, `ci` run 44423686771 | Green (E-46) |

**Why it was built this way.** The tool is read by another service's model, so its output is kept to the minimum that helps
and cannot carry tenant data or free text.

**Trade-offs and alternatives.** A fixed three-field shape limits what RERO can reason about; richer fields were rejected
because they would leak explanation text.

**Not covered / limits of this evidence.** Synthetic rows; one test; RERO itself is cut (F43).

**Pending.** None.

## F42 Repository and documentation chores of the week

| Check | Setup | Result |
|---|---|---|
| CI minutes | `.github/workflows/ci.yml` (PR #24, commit `9991809`) | `paths-ignore: ['**.md', 'docs/**']` on push and pull request; the nightly `schedule` is commented out (run on demand with `workflow_dispatch`) because Actions minutes are capped. Effect on minutes used: to be confirmed |
| Phase 1 to 5 guide | PQ1.2.11 (E-47) | F21 to F31 and the evidence map; every cited path checked on 2026-11-19; docs only |
| Claim alignment | Q4.4 (commit `67c4f14`) | README and `PLATFORM_FACTS.md` claims aligned with the evidence register; docs only |
| Portfolio page | `portfolio/index.html` (commit `1eee921`) | Static responsive page; not tested, no automated check |

**Why it was built this way.** The repository is private with capped Actions minutes, so docs-only pushes should not run the
full build, and a nightly drift scan is traded for an on-demand one. Documentation was tied to the register so a claim can
be checked.

**Trade-offs and alternatives.** A paused nightly scan can miss base-image or dependency drift for days (run it before a
release). Alternative: run the nightly less often; not chosen.

**Not covered / limits of this evidence.** No measurement of minutes saved; a docs-only change is not built at all.

**Pending.** Re-enable the schedule when quota allows (decision for the owner).

## F43 RERO, the sibling service (cut, off by default)

| Check | Setup | Result |
|---|---|---|
| RERO test suite | `pytest -q` in the RERO repo, local run 2026-11-19 | 143 passed in 70.5 s; its README states "143 tests, ruff clean, CI green" |
| Keep or cut | F14 (E-32): frozen rule, 28 cases, real gateway | CUT: criteria C1 to C3 fail (root cause 26/28 against 28/28), C4 to C6 pass; cost 0.0145 USD |
| Default state in reconcile-ai | `reconcile.rero.enabled: ${RERO_ENABLED:false}` | Off by default; the mode is refused with 409 when off |
| Reconcile-ai side tests | F13: fake RERO ITs, concurrency IT (30 parallel against budget 5: 5 ok, 25 x 429), `SimilarBreaksToolIT` (F41) | See F13 and F41 |
| License and headers in RERO | RERO commits `7c1c4ae`, `d4f3ae8` | AI use policy and license notice, plus copyright and canary headers on 24 files; README already states "all rights reserved" and its own canary. A RERO `LICENSE` file is untracked in its working tree on 2026-11-19 (to be confirmed) |
| Contract sync | RERO commit `ccc7032` | Gateway v1 schemas synced (D1.1.5) |

**Why it was built this way.** The keep or cut rule was frozen before any code (E-23), so the result could not be argued
after the fact. It failed three criteria, so the service stays as code and an honest negative result, not a default path.

**Trade-offs and alternatives.** Keeping dead code has a maintenance cost; deleting it would remove the evidence of the
design. Alternative: tune the prompt and re-run; rejected because the rule was fixed and the budget was one run.

**Not covered / limits of this evidence.** One run, 28 cases, one model; RERO's CI status on the header commit was not
checked here (to be confirmed).

**Pending.** None; nothing further planned.

---

## Phase 1 to 5 evidence map

Every path below was checked to exist on 2026-11-19. "CI" means the run is recorded in a register row; otherwise the
result is a local run or a log.

| Feature | Guide | Test classes | Proof files | Register | Status |
|---|---|---|---|---|---|
| Foundation, security, tenant isolation | F21 | `SecurityAndTenantIsolationIT`, `RowLevelSecurityIT`, `KeycloakIT`, `BreakAndEvidenceIT`, `EvidenceContentIT` | none beyond CI | E-01, E-02, E-13, E-16 | CI green |
| Investigation pipeline | F22 | `InvestigationFlowIT`, `GoldenPathIT`, `HybridRetrievalIT`, `AuditChainIT`, `PolicyGateServiceTest` | `docs/proof/phase2a-*.log`, `phase2b-verify.log`, `phase2c-verify.log` | E-03, E-04 | CI green |
| Hardening (breaker, PII, outbox, digest, rate limit) | F23 | `LlmResilienceIT`, `PiiMaskerTest`, `PresidioNerIT`, `KafkaOutboxIT`, `DailyDigestIT`, `RateLimitIT` | ADRs only | E-16 | CI green |
| Audit anchoring | F24 | `AuditAnchorIT` | none | E-54 | CI (suite); real S3 pending |
| Evaluation harness | F25 | `InvestigationEvalIT`, `RetrievalEvalIT`, `EvalCaseLoaderTest`, `OpenAiInvestigationEvalIT` | `docs/proof/phase4-openai-eval.md` | E-10 | gate CI; live local, paid |
| Agent and supervisor modes | F26 | `AgentInvestigationIT`, `SupervisedInvestigationIT`, `SupervisedEvalIT`, `OpenAiAgentEvalIT` | `docs/proof/phase4g-supervised-eval.md`, `4g-live-run3-2026-11-13/` | E-04, E-10 | free ITs CI; live local, paid; rule not met |
| Alert delivery | F27 | `alerts.test.yml`, `ObservabilityIT` | `docs/proof/4c-outage-drill-2026-11-10.md`, `4c-alert-email.txt` | E-55 | local drill; real SMTP pending |
| Bedrock adapter | F28 | `BedrockAdapterIT`, `BedrockFakeEvalIT`, `BedrockEvalIT` (live) | `docs/proof/4d-bedrock-fake-eval-2026-11-10.md`, `docs/proof/eval/report-bedrock-fake-2026-11-10.md` | E-56 | fake CI; live pending |
| AWS Terraform | F29 | `*.tftest.hcl` (8 files) | `docs/proof/4e-localstack-apply-2026-11-10.md` | E-57 | LocalStack CI; real apply pending |
| Dashboard and cache savings | F30 | `ObservabilityIT`, `SemanticCacheIT`, `TieredSemanticCacheIT` | `docs/proof/5c-observability-dashboard-2026-11-15.md`, `5d-semantic-cache-savings-2026-11-15.md` | E-14, E-15 | mechanism CI; figures local |
| UI and Kubernetes | F31, F40 | `ui/src/app/**/*.spec.ts`, kubeconform step | `docs/proof/pq1-2-1-keda-kind-2026-11-19.md` (KEDA on kind, stubs) | E-33, E-21, E-44 | CI (schema and specs); KEDA kind run local with stubs |

F24, F27, F28 and F29 now have register rows (E-54 to E-57, written 2026-11-19: proven locally or on fakes, real AWS and
real SMTP still pending). Still without a recorded run: the manifests-and-HPA run on kind before 2026-11-19 and a browser
end-to-end run of the console. This change does not edit the register.
