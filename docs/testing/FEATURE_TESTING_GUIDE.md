# reconcile-ai: feature testing guide

Scope: F1 to F20 cover features tested between 2026-11-16 and 2026-11-18 (platform stages Q1, Q2, Q3, Q4 and the D1
items). F21 to F31 cover the Phase 1 to Phase 5 features built earlier, and the last section is an evidence map for
them. Every number below comes from a register row in `platform/evidence/EVIDENCE_REGISTER.md` (E-ids), from a proof
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
| [F18](#f18-l1-cache-in-front-of-redis-d121) | L1 cache in front of Redis (D1.2.1) | none yet | default build |
| [F19](#f19-read-only-mcp-server-d122) | Read-only MCP server (D1.2.2) | none yet | default build |
| [F20](#f20-break-specific-knowledge-query-d1212) | Break-specific knowledge query (D1.2.12) | none yet | default build; paid re-measure approved and run |
| [F21](#f21-foundation-security-tenant-isolation-breaks-and-evidence-tier-0-to-1) | Foundation: security, tenant isolation (Tier 0 to 1) | E-01, E-02, E-13 | default build |
| [F22](#f22-investigation-pipeline-tier-2) | Investigation pipeline (Tier 2) | E-03, E-04 | default build |
| [F23](#f23-production-hardening-resilience-pii-outbox-digest-rate-limit-api-hygiene) | Production hardening | E-16 | default build |
| [F24](#f24-audit-anchoring-signed-chain-heads-in-s3-object-lock) | Audit anchoring | none yet | default build; real S3 deferred |
| [F25](#f25-evaluation-harness-phase-4a) | Evaluation harness (4A) | E-10 | gate in build; live paid |
| [F26](#f26-investigation-agent-4b-and-supervisor-agents-4g) | Agent (4B) and supervisor agents (4G) | E-10 | default build; live paid |
| [F27](#f27-alert-delivery-phase-4c) | Alert delivery (4C) | none yet | promtool; real SMTP deferred |
| [F28](#f28-amazon-bedrock-adapter-phase-4d) | Amazon Bedrock adapter (4D) | none yet | fake in build; live deferred |
| [F29](#f29-aws-deployment-with-terraform-phase-4e) | AWS deployment with Terraform (4E) | none yet | LocalStack; real apply deferred |
| [F30](#f30-observability-dashboard-and-semantic-cache-savings-phase-5c-and-5d) | Dashboard and cache savings (5C, 5D) | E-14, E-15 | local run |
| [F31](#f31-console-ui-and-kubernetes-manifests) | Console UI and Kubernetes manifests | E-33, E-21 | CI |

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
  path. I made retrieval deterministic, which also removes the model's chance to choose a wrong or cross-tenant query.
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

**Honest limits.** KEDA is a schema check only; autoscaling behaviour is not tested on a cluster. Per-tenant
credentials ran against a fake gateway and fake realm.

**Interview questions.**

- *How do you test a distributed rate limit?* Real Redis in Testcontainers, 40 concurrent requests, exactly 10 allowed.
- *Did you prove autoscaling?* No. I validated the overlay schema; running KEDA on a cluster is not done.

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

**Evidence.** README features table (Audit row) and "Test suite" row for `AuditAnchorIT`. No separate proof file.

**Deferred to the final phase.** Anchoring to real S3 (PQ1.2.8). MinIO and the LocalStack Object Lock check in CI
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

**Evidence.** `docs/proof/4c-outage-drill-2026-11-10.md`, `docs/proof/4c-alert-email.txt`, CI `ci` (alerts job).

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

**Evidence.** `docs/proof/4d-bedrock-fake-eval-2026-11-10.md`, `docs/proof/eval/report-bedrock-fake-2026-11-10.md`.

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
status tracker), `infra/README.md`.

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

**Evidence.** E-33, E-21. README says the manifests and HPA were validated on kind, but no proof file for that run is in
`docs/proof/`, so treat it as unrecorded. KEDA scaling and browser end-to-end runs are not tested.

---

## Phase 1 to 5 evidence map

Every path below was checked to exist on 2026-11-19. "CI" means the run is recorded in a register row; otherwise the
result is a local run or a log.

| Feature | Guide | Test classes | Proof files | Register | Status |
|---|---|---|---|---|---|
| Foundation, security, tenant isolation | F21 | `SecurityAndTenantIsolationIT`, `RowLevelSecurityIT`, `KeycloakIT`, `BreakAndEvidenceIT`, `EvidenceContentIT` | none beyond CI | E-01, E-02, E-13, E-16 | CI green |
| Investigation pipeline | F22 | `InvestigationFlowIT`, `GoldenPathIT`, `HybridRetrievalIT`, `AuditChainIT`, `PolicyGateServiceTest` | `docs/proof/phase2a-*.log`, `phase2b-verify.log`, `phase2c-verify.log` | E-03, E-04 | CI green |
| Hardening (breaker, PII, outbox, digest, rate limit) | F23 | `LlmResilienceIT`, `PiiMaskerTest`, `PresidioNerIT`, `KafkaOutboxIT`, `DailyDigestIT`, `RateLimitIT` | ADRs only | E-16 | CI green |
| Audit anchoring | F24 | `AuditAnchorIT` | none | none | CI (suite); real S3 deferred |
| Evaluation harness | F25 | `InvestigationEvalIT`, `RetrievalEvalIT`, `EvalCaseLoaderTest`, `OpenAiInvestigationEvalIT` | `docs/proof/phase4-openai-eval.md` | E-10 | gate CI; live local, paid |
| Agent and supervisor modes | F26 | `AgentInvestigationIT`, `SupervisedInvestigationIT`, `SupervisedEvalIT`, `OpenAiAgentEvalIT` | `docs/proof/phase4g-supervised-eval.md`, `4g-live-run3-2026-11-13/` | E-04, E-10 | free ITs CI; live local, paid; rule not met |
| Alert delivery | F27 | `alerts.test.yml`, `ObservabilityIT` | `docs/proof/4c-outage-drill-2026-11-10.md`, `4c-alert-email.txt` | none | local drill; real SMTP deferred |
| Bedrock adapter | F28 | `BedrockAdapterIT`, `BedrockFakeEvalIT`, `BedrockEvalIT` (live) | `docs/proof/4d-bedrock-fake-eval-2026-11-10.md`, `docs/proof/eval/report-bedrock-fake-2026-11-10.md` | none | fake CI; live deferred |
| AWS Terraform | F29 | `*.tftest.hcl` (8 files) | `docs/proof/4e-localstack-apply-2026-11-10.md` | none | LocalStack CI; real apply deferred |
| Dashboard and cache savings | F30 | `ObservabilityIT`, `SemanticCacheIT`, `TieredSemanticCacheIT` | `docs/proof/5c-observability-dashboard-2026-11-15.md`, `5d-semantic-cache-savings-2026-11-15.md` | E-14, E-15 | mechanism CI; figures local |
| UI and Kubernetes | F31 | `ui/src/app/**/*.spec.ts`, kubeconform step | none | E-33, E-21 | CI (schema and specs only) |

Items with no register row (F24, F27, F28, F29, F31 manifests on kind) are candidates for new rows in the platform
register; this change does not edit the register.
