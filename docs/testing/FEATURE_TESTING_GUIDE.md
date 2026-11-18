# reconcile-ai: feature testing guide

Scope: features tested between 2026-11-16 and 2026-11-18 (platform stages Q1, Q2, Q3.1 to Q3.6, Q4). Every number below
comes from a register row in `platform/evidence/EVIDENCE_REGISTER.md` (E-ids) or from a proof document in
`docs/proof/`. Features from Phase 1 to Phase 5 that were tested earlier are not covered yet (see the final-phase task
in `platform/docs/STATUS_TRACKER.md`).

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
| [F14](#f14-q37-rero-keepcut-evaluation) | Q3.7 RERO keep/cut evaluation | pending | running |

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

Status: **done 2026-11-18, decision CUT (E-32).** Paid run (approved), both engines on gpt-4o-mini through the real ai-gateway, knowledge off for both, same day. C1 fail (HRD-002: RERO sent a wrong cause to L1 where the baseline chose manual review), C2 fail (root cause 26/28 vs baseline 28/28; McNemar p = 0.50, not significant), C3 fail (evidence recall 23/24 vs 24/24); C4 pass (cost 1.34x), C5 pass (p95 6522 vs 5460 ms), C6 pass (no budget overrun, no duplicate runs, no unknown outcomes). Spend 0.0145 USD from the gateway ledger. RERO stays disabled by default; the Q1/Q2 three-service platform is the final shape. Interview point: the rule was frozen before the code and the negative result is published as it stands. Full report: `reconcile-ai/docs/proof/q3-7-rero-keep-cut-2026-11-18.md`. Original note: The rule was frozen before Q3 code (`docs/adr/RERO_EVALUATION_RULE.md`, E-23);
the default decision is CUT. This section will list the cases, the run date, the numbers and the decision.
