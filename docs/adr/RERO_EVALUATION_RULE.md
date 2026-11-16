# RERO evaluation rule (Q2 review gate)

- **Date fixed:** 2026-11-16
- **Baseline code:** `e15a7e16cb947938051b36f35e9ff8f8be5d918a` (Q2.3 to Q2.7 gateway governance), plus the
  gateway-route review fixes committed together with this file.
- **Freeze rule:** this file must be committed **before** any Q3 (RERO) code. The commit that adds it is the freeze
  point. `git log --follow docs/adr/RERO_EVALUATION_RULE.md` must show no change after the first Q3 commit, apart from
  filling in the results section at Q3.7.

## 1. Gate decision: conditional GO, as a bounded experiment

**Question: is RERO justified beyond adding a second language (Python/LangGraph) to the portfolio?**

On the evidence so far, **only weakly**:

- Single call already scores 27/28 on root cause with a real model (`docs/proof/phase4-openai-eval.md`). On these 28
  cases RERO can at best fix **one** case. A gain cannot be shown on this set; only a loss can.
- Both earlier multi-step attempts lost on cost: the agent was 8x the cost and 3.7x the latency for one more correct
  cause (4B), and the supervised modes missed their fixed rule (7.9x and 12.3x cost against a 5x limit, 4G).
- What RERO could add that the local engine cannot: recommendations grounded in **runbooks and policies from EKA**,
  as a separate service with its own identity and run budget. That is an architecture benefit (platform shape,
  service isolation, a second engine behind the same policy gate), not a measured quality benefit.

So Q3 may start, but only as an experiment with a cut decision fixed in advance. **The default at Q3.7 is CUT.**
KEEP needs every criterion in section 3 to pass. Passing allows KEEP; it does not prove RERO is better.

## 2. Control gaps found at the gate

| Gap | State at this date |
|---|---|
| Agent and supervised modes called the model directly, bypassing ai-gateway when the route was on | **Fixed:** refused with 409 `MODE_NOT_GOVERNED` while the route is on (`GatewayReviewFixesIT`) |
| A node that stalled past the lease could write a proposal after recovery had failed the job | **Fixed:** late results are discarded; stage updates on a terminal job are no-ops (`GatewayReviewFixesIT`) |
| Recovery flagged unknown outcomes for jobs that never used the gateway | **Fixed:** SINGLE_CALL only |
| Token-endpoint failures and unexpected gateway codes were not logged as typed, not-sent rows | **Fixed:** `GATEWAY_TOKEN_UNAVAILABLE`; codes sanitised before storage |
| Q1 / S-1 to S-3: no recorded proof that each service token works only at its intended service | **Open**: needs the shared Keycloak realm and EKA running (task 4) |
| No live run through a real ai-gateway yet (fakes and contract schemas only) | **Open** |
| Heartbeat refreshes only on a stage change; one long model stage can approach the lease | **Open, mitigated**: the late-result guard above makes it safe, not just unlikely |
| RERO identity (audience `rero`, `RERO_SERVICE` role, run tokens) | **Not built**: Q3.1 |

The open gaps do not block Q3.1 to Q3.6, which run on fakes. They do block calling any result "production-ready".

## 3. The rule (fixed now, applied at Q3.7)

### 3.1 Cases and runs
- **Primary set:** the 28 labelled cases in `src/test/resources/eval/cases/`, unchanged. Their file hashes are
  recorded in the Q3.7 report; a changed case invalidates the run.
- **Secondary set:** the 12 hard cases in `cases-hard/`, reported but **not** used for the decision.
- Same chat model for both engines (`gpt-4o-mini`, temperature 0), same embeddings, same policy gate configuration,
  same day. Baseline = SINGLE_CALL re-run on that day, not the 2026-11-09 numbers.
- One run per engine. A run that fails for infrastructure reasons (not model output) may be repeated once, and the
  failed run is kept in the report.

### 3.2 Criteria (all must pass for KEEP)
| # | Criterion | Threshold |
|---|---|---|
| C1 | Zero policy regressions | No AUTO_RESOLVE. No case where RERO's route sends a **wrong** cause to an approver while the baseline sent that case to manual review. All existing safety ITs (no-resolve, tenant isolation, maker-checker, single resolution path) pass with RERO enabled. |
| C2 | Root-cause non-regression | Paired on the same 28 cases: cases right in baseline and wrong in RERO ≤ 1, and RERO total ≥ baseline total − 1 |
| C3 | Citation non-regression | Cases where a cause-claiming answer has an unverified citation: RERO ≤ baseline. Required-evidence recall: RERO ≥ baseline |
| C4 | Cost ceiling | Mean cost per case ≤ **5x** baseline, read from the **gateway ledger** (`actualCostUsd`), not from estimates |
| C5 | Latency ceiling | End-to-end p95 (job start to terminal state) ≤ **4x** baseline p95 and ≤ **15 s** absolute |
| C6 | Budget and idempotency | No run exceeds its tool budget; no duplicate run for one key; no unknown outcome left unreconciled |

### 3.3 Interval method
- Each proportion is reported as a count and a **95% Wilson score interval** (e.g. 27/28 → 82.3% to 99.4%).
- The engines are compared **paired** on the same cases. Report the discordant counts (baseline-only right, RERO-only
  right) and an **exact two-sided McNemar (binomial) p-value**.
- With n = 28, exact McNemar reaches p < 0.05 only with at least 6 discordant cases all in one direction (about 21 points), so smaller differences cannot be detected. The report must say so, and must not call
  a difference "better" unless C2 passes **and** McNemar p < 0.05. That is not expected on this set.
- Latency p95 uses the nearest-rank method over all 28 cases. Cost is the mean, with min and max.

### 3.4 No post-hoc changes
- None of section 3 changes after the first Q3 commit: not the cases, thresholds, models, metrics or interval method.
- If a criterion turns out to be wrong, the run is still judged by this rule. A new rule goes into a new dated file
  for a **new** run, and the first result is published as it stands.
- Prompt or code changes made after seeing Q3.7 results mean a new run under a new rule file, with both runs reported.
- A failed result is published as a negative result, as was done for 4G.

## 4. Results (filled at Q3.7 only)

_Empty until Q3.7. Record: run date, commit SHA, case-file hashes, per-criterion pass/fail with counts and intervals,
the decision (KEEP or CUT), and links to the raw reports._
