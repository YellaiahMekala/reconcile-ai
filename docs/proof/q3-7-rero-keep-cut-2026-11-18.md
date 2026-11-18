# Q3.7 RERO keep/cut evaluation (2026-11-18)

**Decision: CUT.** Three of the six criteria fail on the primary set (C1, C2, C3). RERO stays disabled by default
(`reconcile.rero.enabled=false`, unchanged). Under the IMPLEMENTATION_PLAN cut rule, the Q1/Q2 three-service
platform is the final project. This is a negative result, published as it came out.

Judged strictly by `docs/adr/RERO_EVALUATION_RULE.md` section 3 (frozen; not edited). One run per engine. No
infrastructure failure happened, so nothing was repeated.

## 1. Setup

| Item | Value |
|---|---|
| Date | 2026-11-18, both engines in the same JVM and application context, run back to back |
| reconcile-ai commit | `baa77bbd4439582acf1a89a313ef4dd9c38cd799` plus the uncommitted Q3.7 test files listed in section 9 (test code only, no main-code change) |
| rero commit | `b2ba078` (unchanged), run locally with uvicorn, `MODEL_ADAPTER=gateway`, `GATEWAY_BASE_URL=http://localhost:8280`, JWKS of the platform realm, `RECONCILE_INTERNAL_BASE_URL=http://localhost:8087` |
| Chat model | `gpt-4o-mini` via the real ai-gateway (real-provider profile). Ledger `model_key` = `openai-gpt-4o-mini` on all 115 OK rows |
| Temperature | 0.0 for both. reconcile-ai sends `settings.temperature=0.0` (`InvestigationAiClient` to `HttpGatewayChatAdapter`). RERO's `GatewayModel` sends `temperature: 0.0`. The gateway's OpenAI adapter forwards `settings.temperature` unchanged |
| Embeddings | Same for both: the test hashing embedding model of the `test`/`mock-llm` profile, as in E-29/E-30 |
| Policy gate | Same Spring context, so the policy configuration is identical |
| Knowledge | **OFF for both** (chosen before the run). E-30 showed the EKA pre-step hurts routing on this corpus (route 67.9% on vs 85.7% off), and RERO's knowledge tool would confound the comparison. With knowledge off, the SINGLE_CALL pre-step is skipped, and RERO's `knowledge` tool returns `UNAVAILABLE` with no procedures |
| Baseline | SINGLE_CALL re-run today on the same path (gateway route on, knowledge off), not the 2026-11-09 numbers |
| Identity | Platform Keycloak realm `platform`, client `svc-reconcile-tenant-a`. The token issued with scopes `aud-rero-api aud-ai-gateway` carries `aud=[ai-gateway, rero-api]`, `roles=[RERO_CALLER, GATEWAY_CONSUMER]`, `tenant_id=tenant-a`. The realm import already had these, so no realm change or script was needed. ai-gateway accepts a multi-audience token that contains `ai-gateway` (`ServiceTokenIT`). `reconcile.rero.model-via-gateway=true` |
| Run tokens | Throwaway ES256 P-256 pair generated into the git-ignored `platform/.golden/` folder. Not committed |
| Gateway budget | Default tenant ceiling 0.50 USD (golden stack). Task guard 0.10 USD |
| Tool budget | RERO `max-tool-calls` 8, deadline 60 s (defaults) |

Test: `ReroPlatformEvalIT` (tag `platform-live`, gated by `GATEWAY_LIVE_APPROVED=yes` and `RERO_LIVE_BASE_URL`;
excluded from the default build). Cost per case comes from the gateway ledger: the sum of `actual_cost_usd` over the
rows whose `idempotency_key` contains the job id. SINGLE_CALL uses `recon:{job}:...` and RERO uses
`{job}:draft` / `{job}:selfcheck`. Latency is measured from job `created_at` to `finished_at` (job start to terminal),
p95 by nearest rank.

### Case-file hashes (SHA-256)

Primary set: `src/test/resources/eval/cases/`, 28 files. They are unchanged since `ec7063b` and the working tree is clean.

```
386d5b443aa23d9b1bf5c2ea1a11251f10e0a7a85c5f6ebfccae2e516bfd693f DAT-001.json
8ca2b41020a717dd1370efa21141fdb455b4b6ef8585aa6f4f26031d9f0e8fc8 DAT-002.json
e19e7c0ac1191c345e29f947a6b06a5e0177be711357be9ccf403383d6edd7b4 DAT-003.json
68556b2d5e6d6e4fcfc53ebf7668a2f5bf44e362d4b62d5958556d90df4732b9 DUP-001.json
5b5d6817903cb2342112f7a488826c098f4a33d90aa0c4e58cbffdf5f5ee1103 DUP-002.json
ba74766ef42956dcfce814a2f7c15a27809d90854b8761668535077887a4d764 DUP-003.json
c6cc5e197d4d6365952c4b5694a8c5698846c0e17b3c4bcc810dc2cbfcfc470b FEE-001.json
9dcb298c450e1b7531f406c1d191a0f954727f06f0e67a7a00e09f1d28148cb2 FEE-002.json
ce206797f8c443ca87b659f288e02813ab4835bfde1c9a7948d9a090bc63312e FEE-003.json
67b2f1869d9a21216f42d9c7c41dcd7900e8eafccc3d1603849bffb9de3475a0 FEE-004.json
70c08b0b6dd6bf97d50b5bea97cf8f6b5109c808f4e6c3f2fca185bad8aea01f FXR-001.json
a4fa11506073bc02ad2472eedf8b22c81f2af08e962866e69f0dfec5b66bad86 FXR-002.json
7361996e4acc631296d9fb2714926ab6f8705d0036efd8ea6c0a132f11fde284 FXR-003.json
8167686a2303764a6c8172ce9154aa9719ee0b28131b687c4c566add0a9c0782 HRD-001.json
f74785188d824dce8116351a94a732b2ea449d2ab0430047816da1df0f9ca019 HRD-002.json
39dcdd6cff361c1f3ea011033adacfb3420c873bcd6059b508fa39ff38e974f0 MIS-001.json
740aa10b088fd6670cca6da2d569f737fa19cbb8c1ec3bc950ddd4b4a09195d9 MIS-002.json
75404ba6823c8bfb20a7d02acd7e7f9f73526e7f70a76cc699a413dad3ba30f6 MIS-003.json
d00cb8a7d9e4da60adda3bd39019b74fbcffa1cc2a5a1f8ecba263cee4bc1563 PAR-001.json
df67dd861cc22f0a5855e6a79eff9ddec934e577bcb29d2530019f4daeee16ea PAR-002.json
71b43e3dec5ce211aa16f2a155c920ff9be9ab0003d2f5a79f1597fcc575000d PAR-003.json
5a9a46c746819bac0398c15fe7fa4cdb11de5ad9123873c11acc9169d690aab1 TIM-001.json
239b93aa7f7baf8f969159a3013bdd2b585378f555acf7d72227ecab5fa158c8 TIM-002.json
ca5d3b758b337ec171c8a93d3862ea0e532db1acc6e25dd03bd92c476731d00e TIM-003.json
b2f21e909f5175e1eb9474b1014faf9e4ad3ee7e45ae38b13e6a8daa3efb5dcc TIM-004.json
40e005e3b4a9750c1a7ca460a3681b5023635e6b35e9ab5da80b9d35e56ba6d5 UNK-001.json
3d7c19523f70fe44c0d103354049fe193b6fc2273ca00607c49e89b44827a404 UNK-002.json
4744b1edcce69a6ced3eec3964c7994d3a013d438b13323cc58f30a9c08f3c63 UNK-003.json
```

Secondary set: `cases-hard/`, 12 files.

```
c6104a1325a672c1bec74e494c7af53985fae134c70385a967b7bc0f129dc889 HRD-003.json
58108feaa04a0e67f9f1065a222e69c75790cdb89ec540a0b3a428f270a1b205 HRD-004.json
121bf8bca368ed7dc62905d4caf638ca52647863a159d338b4647ba84c274936 HRD-005.json
c18dc310f01398d8ff23f571eb6d54516396ba6bc99e2f4adf202515e219a9b0 HRD-006.json
e58a63273eb6fb79c58e908e75554dc9e58b08e9abf9e78bb6f2eaf209a83479 HRD-007.json
772d46c8d44093854691f2def42c874198501335bca5d9a317b61241f6d3cf95 HRD-008.json
abaca02bcd11c0c39cf14970a8c852130924e0c2180522778a9797496932edaf HRD-009.json
c6f7e7868ede96d3bcc6519c5212c6dba764038363238e8a496a4f40bee1501d HRD-010.json
3ac829dc58917555a5cd19728d0b5efed6c8e68a2dbcf142fd2e49b6e1612943 HRD-011.json
ce8b5000bcfebb7710bd5b53b2466c89948bc57df6bb7f80bb1540f554584e8d HRD-012.json
174f84195464848fd7d166651f7638ba432a5f384d121d2ba9d9902b7cd4a3fa HRD-013.json
a7d8bb899004dd9318b30f011053732fc7ebf76d946caea0bb63d7eacf1cc473 HRD-014.json
```

## 2. Primary set (28 cases): per-criterion result

| # | Result | Counts and intervals (95% Wilson) |
|---|---|---|
| C1 | **FAIL** | AUTO_RESOLVE: 0 in both engines. One case where RERO sent a **wrong** cause to an approver while the baseline sent it to manual review: **HRD-002** (expected UNKNOWN; baseline UNKNOWN / MANUAL_REVIEW; RERO TIMING_DIFFERENCE / L1_APPROVAL). Safety ITs with RERO enabled: pass (section 5) |
| C2 | **FAIL** | Baseline 28/28 (87.9% to 100.0%); RERO 26/28 (77.4% to 98.0%). Right in baseline only: **2** (HRD-001, HRD-002), limit ≤ 1. Right in RERO only: 0. RERO total 26 < baseline − 1 = 27. Exact McNemar p = 0.50 |
| C3 | **FAIL** | Cause-claiming answers with an unverified citation: baseline 0, RERO 0 (pass). Required-evidence recall: baseline 24/24 (86.2% to 100.0%), RERO **23/24** (79.8% to 99.3%). RERO < baseline, so it fails |
| C4 | PASS | Mean cost per case (ledger `actual_cost_usd`): baseline 0.00014906 USD (min 0, max 0.00019020, total 0.0041736); RERO 0.00019923 USD (min 0, max 0.00024540, total 0.0055785); **1.34x** (limit 5x) |
| C5 | PASS | p95 job start to terminal: baseline 6522 ms, RERO 5460 ms; **0.84x** (limit 4x), under 15 s |
| C6 | PASS | Runs over tool budget: 0 (every RERO run used 4/8 tools). Duplicate runs for one key: 0 (ledger: no key with more than one row; RERO store: 40 runs = 40 distinct job ids = 40 distinct keys across both sets). Unknown outcomes left: 0 |

Routes (reported, not a criterion): baseline 26/28 correct (92.9%), RERO 24/28 (85.7%).

The rule's significance limit: with n = 28, exact McNemar reaches p < 0.05 only with at least 6 discordant cases, all
in one direction. Here there are 2 discordant cases, both baseline-only, so p = 0.50. The difference is **not
statistically significant in either direction**. This report does not claim the baseline is "better". The cut
follows from the rule's non-regression thresholds (C1 to C3), not from a significance test.

### Per-case paired table (primary)

| Case | Expected | Baseline cause / route | RERO cause / route | RERO failure | Base ms | RERO ms | Base USD | RERO USD | RERO tools |
|---|---|---|---|---|---|---|---|---|---|
| DAT-001 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR / L1_APPROVAL | DATA_ENTRY_ERROR / L1_APPROVAL |  | 12380 | 6542 | 0.00016770 | 0.00020070 | 4/8 |
| DAT-002 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR / L1_APPROVAL | DATA_ENTRY_ERROR / L1_APPROVAL |  | 3217 | 3252 | 0.00015675 | 0.00017940 | 4/8 |
| DAT-003 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR / L2_APPROVAL | DATA_ENTRY_ERROR / L2_APPROVAL |  | 3813 | 3215 | 0.00015555 | 0.00019185 | 4/8 |
| DUP-001 | DUPLICATE_ENTRY | DUPLICATE_ENTRY / L2_APPROVAL | DUPLICATE_ENTRY / L2_APPROVAL |  | 2980 | 3752 | 0.00016560 | 0.00022485 | 4/8 |
| DUP-002 | DUPLICATE_ENTRY | DUPLICATE_ENTRY / L1_APPROVAL | DUPLICATE_ENTRY / L1_APPROVAL |  | 4028 | 3723 | 0.00016920 | 0.00019020 | 4/8 |
| DUP-003 | DUPLICATE_ENTRY | DUPLICATE_ENTRY / L2_APPROVAL | DUPLICATE_ENTRY / L2_APPROVAL |  | 2987 | 4256 | 0.00016290 | 0.00021465 | 4/8 |
| FEE-001 | FEE_OR_CHARGE | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / L1_APPROVAL |  | 3071 | 3743 | 0.00016845 | 0.00022095 | 4/8 |
| FEE-002 | FEE_OR_CHARGE | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / L1_APPROVAL |  | 3083 | 4240 | 0.00015810 | 0.00020700 | 4/8 |
| FEE-003 | FEE_OR_CHARGE | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / L1_APPROVAL |  | 2923 | 3285 | 0.00016095 | 0.00018870 | 4/8 |
| FEE-004 | FEE_OR_CHARGE | FEE_OR_CHARGE / L2_APPROVAL | FEE_OR_CHARGE / L2_APPROVAL |  | 2964 | 4342 | 0.00016035 | 0.00021300 | 4/8 |
| FXR-001 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE / L1_APPROVAL | FX_RATE_DIFFERENCE / L1_APPROVAL |  | 2538 | 4586 | 0.00016275 | 0.00023370 | 4/8 |
| FXR-002 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE / L1_APPROVAL | FX_RATE_DIFFERENCE / L1_APPROVAL |  | 3438 | 4347 | 0.00017040 | 0.00020880 | 4/8 |
| FXR-003 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE / L1_APPROVAL | FX_RATE_DIFFERENCE / L1_APPROVAL |  | 2702 | 4349 | 0.00016140 | 0.00019605 | 4/8 |
| HRD-001 | FEE_OR_CHARGE | FEE_OR_CHARGE / L1_APPROVAL | FAILED | RERO_MODEL_BLOCKED | 3088 | 667 | 0.00019020 | 0 | 4/8 |
| HRD-002 | UNKNOWN | UNKNOWN / MANUAL_REVIEW | TIMING_DIFFERENCE (wrong) / L1_APPROVAL |  | 3104 | 3316 | 0.00016320 | 0.00018435 | 4/8 |
| MIS-001 | MISSING_ENTRY | MISSING_ENTRY / L1_APPROVAL | MISSING_ENTRY / L1_APPROVAL |  | 4400 | 4289 | 0.00017370 | 0.00023910 | 4/8 |
| MIS-002 | MISSING_ENTRY | MISSING_ENTRY / L1_APPROVAL | MISSING_ENTRY / L1_APPROVAL |  | 6522 | 3791 | 0.00016440 | 0.00022350 | 4/8 |
| MIS-003 | MISSING_ENTRY | MISSING_ENTRY / L2_APPROVAL | MISSING_ENTRY / L2_APPROVAL |  | 3044 | 5460 | 0.00016800 | 0.00021885 | 4/8 |
| PAR-001 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT / L1_APPROVAL | PARTIAL_SETTLEMENT / L1_APPROVAL |  | 3015 | 4843 | 0.00016845 | 0.00021810 | 4/8 |
| PAR-002 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT / L1_APPROVAL | PARTIAL_SETTLEMENT / L1_APPROVAL |  | 2821 | 4827 | 0.00016005 | 0.00021150 | 4/8 |
| PAR-003 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT / L1_APPROVAL | PARTIAL_SETTLEMENT / L1_APPROVAL |  | 3012 | 3761 | 0.00017745 | 0.00021525 | 4/8 |
| TIM-001 | TIMING_DIFFERENCE | TIMING_DIFFERENCE / L1_APPROVAL | TIMING_DIFFERENCE / L1_APPROVAL |  | 3711 | 4287 | 0.00018345 | 0.00024540 | 4/8 |
| TIM-002 | TIMING_DIFFERENCE | TIMING_DIFFERENCE / L1_APPROVAL | TIMING_DIFFERENCE / L1_APPROVAL |  | 3662 | 4291 | 0.00017640 | 0.00022395 | 4/8 |
| TIM-003 | TIMING_DIFFERENCE | TIMING_DIFFERENCE / L1_APPROVAL | TIMING_DIFFERENCE / L1_APPROVAL |  | 3224 | 3778 | 0.00016245 | 0.00021525 | 4/8 |
| TIM-004 | TIMING_DIFFERENCE | TIMING_DIFFERENCE / L2_APPROVAL | TIMING_DIFFERENCE / L2_APPROVAL |  | 2802 | 4343 | 0.00016575 | 0.00021765 | 4/8 |
| UNK-001 | UNKNOWN | UNKNOWN / MANUAL_REVIEW | UNKNOWN / MANUAL_REVIEW |  | 108 | 4270 | 0 | 0.00016800 | 4/8 |
| UNK-002 | UNKNOWN | UNKNOWN / MANUAL_REVIEW | UNKNOWN / MANUAL_REVIEW |  | 65 | 3776 | 0 | 0.00016245 | 4/8 |
| UNK-003 | UNKNOWN | UNKNOWN / MANUAL_REVIEW | UNKNOWN / MANUAL_REVIEW |  | 63 | 3751 | 0 | 0.00016530 | 4/8 |

Notes:
- **UNK-001 to 003:** the baseline costs 0 and takes about 0.1 s because the local engine settles these cases before
  any model call. RERO always makes both model calls (draft and self-check). That is why RERO's cost minimum is above
  0 on these cases.
- **The 0 minimum in RERO's cost** is HRD-001, where the gateway blocked the request before any provider call.

## 3. Failures

| Case | Engine | What happened | Classified as |
|---|---|---|---|
| HRD-001 (primary) | RERO | Draft call refused by the gateway: ledger status `SENSITIVITY_BLOCKED`, cost 0. RERO mapped it to `MODEL_BLOCKED` and the job failed with `RERO_MODEL_BLOCKED` (no proposal). The baseline got the same case right through the same gateway. Probable cause, not verified: reconcile-ai PII-masks its whole prompt before sending, while RERO builds its draft prompt from tool output and its own template, and something in it tripped the gateway's sensitivity screen | **Not** an infrastructure failure: a deterministic policy outcome of RERO's prompt. Not repeated (rule 3.1) |
| none | SINGLE_CALL | All 28 + 12 succeeded | n/a |

No transport errors, timeouts, deadline hits, token failures or unknown outcomes in either engine.

## 4. Secondary set (12 hard cases), reported only and not used for the decision

It was cheap (0.0048 USD for both engines), so it was run.

| # | Result | Detail |
|---|---|---|
| C1 | fail | AUTO_RESOLVE 0. HRD-007: RERO PARTIAL_SETTLEMENT (wrong) / L1_APPROVAL where the baseline sent TIMING_DIFFERENCE to MANUAL_REVIEW |
| C2 | fail | Baseline 12/12 (75.8% to 100.0%), RERO 10/12 (55.2% to 95.3%); baseline-only 2 (HRD-007, HRD-009), RERO-only 0; p = 0.50 |
| C3 | pass | Unverified-citation claims 0 vs 0; recall 12/12 vs 12/12 |
| C4 | pass | 0.00017508 vs 0.00022135 USD mean (1.26x) |
| C5 | pass | p95 4621 ms vs 5513 ms (1.19x) |
| C6 | pass | 0 over budget, 0 duplicates, 0 unknown |

Routes: baseline 10/12, RERO 8/12. RERO sent three correct causes to MANUAL_REVIEW (HRD-006, HRD-010, HRD-011)
where the baseline sent them to L1. That is safe, but it means more manual work. On the primary set this did not
happen.

## 5. C1 safety ITs with RERO enabled

Run with `RERO_ENABLED=true` plus the throwaway run-token keys in the environment, so every application context
started with the RERO mode on. The RERO-specific ITs point at `FakeReroServer`.

- Unit: `ResolutionPathArchitectureTest` (single resolution path), `ReroArchitectureTest`, `InternalApiReadOnlyTest`,
  `ReroInvestigationTest`: **16/16 pass**.
- IT: `SecurityAndTenantIsolationIT`, `RowLevelSecurityIT`, `VirtualThreadsTenantIsolationIT` (tenant isolation),
  `GoldenPathIT` (maker-checker: L1 resolved only by a different checker, L2 needs an L2 checker),
  `InvestigationFlowIT`, `ReroInvestigationIT` (includes "the job response still never resolves", which is the
  no-resolve check), `ReroGovernanceIT`, `ReroToolBudgetConcurrencyIT`, `RunTokenIT`, `GatewayReviewFixesIT`:
  **73/73 pass**.
- The first attempt also set `RERO_MODEL_VIA_GATEWAY=true`. That made `ReroGovernanceIT` fail (it got
  `EVIDENCE_NOT_READY`, expected `MODE_NOT_GOVERNED`), because the test checks the default `model-via-gateway=false`
  and the environment overrode it. This was a harness setting, not a regression. It was re-run with only
  `RERO_ENABLED=true`, and all tests passed. Both logs are kept in `platform/.golden/` (local, git-ignored).

## 6. Spend

| Item | USD |
|---|---|
| Gateway ledger at start of this task | 0 rows (fresh ledger volume) |
| Primary run, both engines (79 OK rows + 1 SENSITIVITY_BLOCKED at 0) | 0.00975210 |
| Secondary run, both engines (36 OK rows) | 0.00475710 |
| **This task total (ledger `actual_cost_usd`)** | **0.01450920** (guard 0.10) |
| Day total incl. previous ~0.0144 | ~0.0289 |

## 7. What this does and does not prove

It **does** show the following:
- On 28 labelled cases, with the same model, temperature, embeddings, policy gate and day, RERO (a fixed LangGraph
  draft and self-check over reconcile-ai's read-only tools) does not meet the frozen non-regression bar. It lost 2
  root causes that the baseline got right, sent one wrong cause to an approver that the baseline had sent to manual
  review, and found one fewer required evidence file.
- The integration works end to end on the real platform. Real Keycloak tokens carry two audiences, RERO calls
  through the real ai-gateway with the caller's token, the run-token-scoped internal API is used, the tool budget is
  enforced (4/8 every run), runs are idempotent, the ledger reconciles per job, and no AUTO_RESOLVE happens.
- RERO was cheap and fast here: 1.34x cost and 0.84x p95. Cost and latency were not the problem. Quality and policy
  were.

It **does not** show the following:
- That RERO is worse in general. 2 discordant cases give p = 0.50, and 28 cases cannot detect differences smaller
  than about 21 points.
- Anything about RERO with knowledge on. That was deliberately excluded (see setup).
- Anything about other models, prompts or corpora. Prompt changes after seeing these results would need a new rule
  file and a new run (rule 3.4). None was made.
- Production readiness of either engine. The open gaps in the rule's section 2 still apply.

## 8. Decision and action

**CUT.** C1, C2 and C3 fail, and KEEP needs all six. Under the IMPLEMENTATION_PLAN cut rule:
- RERO stays disabled. `reconcile.rero.enabled` defaults to `false` (`RERO_ENABLED:false` in `application.yml`), and a
  RERO job is refused with 409 `RERO_DISABLED`. No code change was needed. `ReroDisabledByDefaultIT` covers this in
  the default build.
- The Q1/Q2 three-service platform (reconcile-ai, EKA, ai-gateway) is the final project. The rero repo and the
  reconcile-ai RERO code remain as a documented, switched-off experiment with this negative result.

## 9. Files and raw reports

- Raw per-case CSVs, comparison tables and harness reports: `docs/proof/q3-7-raw/`
- `src/test/java/com/reconcileai/eval/ReroPlatformEvalIT.java`: paid run (tag `platform-live`, gated)
- `src/test/java/com/reconcileai/eval/ReroKeepCutReport.java`: per-case CSV and the C1 to C6 comparison
- `src/test/java/com/reconcileai/eval/ReroKeepCutStats.java` + `ReroKeepCutStatsTest.java`: Wilson, exact McNemar,
  nearest-rank p95 (unit tested, including the rule's 27/28 = 82.3% to 99.4% example), plus a comparison threshold
  test

Default build after these changes (`mvn clean verify`, no external services): **202 unit / 224 IT, all green**.
The 202 includes the 5 new `ReroKeepCutStatsTest` tests. `ReroPlatformEvalIT` is excluded by its tag.

Reproduce: start `platform/scripts/golden/up.sh`, run rero with the environment in section 1, then
`GATEWAY_LIVE_APPROVED=yes RERO_LIVE_BASE_URL=http://localhost:8095 RERO_EVAL_SAFETY_ITS=pass KC_SVC_TENANT_A_SECRET=...
RERO_RUN_TOKEN_PRIVATE_KEY_PEM=... RERO_RUN_TOKEN_PUBLIC_KEY_PEM=... mvn verify
"-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway,gateway-live,eka" -Dit.test=ReroPlatformEvalIT -Dtest=NONE
-Dsurefire.failIfNoSpecifiedTests=false` (`RERO_EVAL_SET=hard` for the secondary set). This is PAID: get approval
first.
