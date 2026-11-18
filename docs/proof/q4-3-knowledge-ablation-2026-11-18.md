# Q4.3 ablation: gateway route on, knowledge pre-step off (2026-11-18)

Status: **run 2026-11-18, local, uncommitted at the time of writing.** Paid run, approved (OpenAI `gpt-4o-mini` through the
real ai-gateway). Same 28 labelled cases, same test hashing embeddings, same day as the baseline and integrated runs.
Synthetic author-written cases, not a benchmark.

Purpose: the integrated run (`q4-3-integrated-eval-2026-11-18.md`) dropped route accuracy from 24/28 to 19/28 and could
not say whether the EKA knowledge pre-step or the gateway route caused it. This run changes one thing: the gateway route
stays on and the knowledge pre-step is off.

How: `IntegratedPlatformEvalIT` with `EVAL_KNOWLEDGE=off` (a new switch in that test; tag `platform-live`, excluded from
the default build and from CI).

## 1. Three-way comparison

| Metric | Baseline (direct, no knowledge) | Gateway only (this run) | Integrated (EKA + gateway) |
|---|---|---|---|
| Cases | 28 | 28 | 28 |
| Root cause correct | 27/28 | 27/28 (96.4%) | 27/28 |
| Route correct | 24/28 (85.7%) | 24/28 (85.7%) | 19/28 (67.9%) |
| Cause-claiming answers whose citations verified | 100% | 96.0% | 100% |
| Required evidence cited (recall) | 100% | 100% | 100% |
| Latency p50 / p95 | 2634 ms / 3555 ms | 2853 ms / 4063 ms | 2779 ms / 3825 ms |
| Tokens in / out | 15964 / 2952 | 15951 / 2969 | 24414 / 2989 |

The 96.0% citation figure is the HRD-002 case (root cause TIMING_DIFFERENCE where UNKNOWN was expected, citations
invalid, routed to MANUAL_REVIEW with CITATIONS_INVALID). It is the same wrong-cause case as in the other two runs.

## 2. The five cases that changed in the integrated run

With knowledge off, all five return to the baseline route.

| Case | Expected route | Baseline | Gateway only | Integrated |
|---|---|---|---|---|
| MIS-001 | L1_APPROVAL | L1_APPROVAL | L1_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| MIS-002 | L1_APPROVAL | L1_APPROVAL | L1_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| MIS-003 | L2_APPROVAL | L2_APPROVAL | L2_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| TIM-001 | L1_APPROVAL | L1_APPROVAL | L1_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| HRD-002 | MANUAL_REVIEW | MANUAL_REVIEW (CITATIONS_INVALID) | MANUAL_REVIEW (CITATIONS_INVALID) | L1_APPROVAL |

## 3. Gateway ledger check

- 25 OK rows, actual cost **0.00417405 USD**.
- Ledger tokens match the harness totals exactly (15951 in / 2969 out).
- 28 cases against 25 ledger rows: 3 cases made no model call. This was **not investigated further** and the reason is
  not claimed here. (In the integrated run the same count was attributed to the 3 no-evidence cases; that was not
  re-checked for this run.)

## 4. Honest reading

- **The gateway route alone does not change routing:** 24/28 (85.7%) equals the direct baseline 24/28 (85.7%). Root cause
  is 27/28 in all three columns.
- **The route drop to 67.9% is attributable to the knowledge pre-step on this corpus.** tenant-a's EKA corpus holds only
  fee procedures, so missing-entry and timing cases received irrelevant procedures and the model reported lower
  confidence. The mechanism (irrelevant procedures lowering confidence) is the working explanation; the ablation shows
  the knowledge step is the factor, not that this is the exact mechanism.
- **Still n = 28, no significance claim.** Against the baseline there are 5 discordant cases split 4 to 1. An exact
  two-sided binomial (sign) test on 5 discordant pairs split 4/1 gives p = 2 x (1 + 5) / 32 = **0.375**. One run per
  column; the model is not deterministic, so a repeat could differ.
- Not measured: the 8450 extra prompt tokens in the integrated run are consistent with the added procedures, but
  per-case prompt sizes were not broken down.

## 5. Implication and recommended next step (not done)

This is a prompt-relevance issue, not a gateway issue. Next step: include procedures only above a relevance threshold or
when they match the break category, and keep the knowledge pre-step fail-open by default. Neither change is made yet, and
re-measuring it would need a new paid-run approval.

## 6. Spend

| Item | USD |
|---|---|
| This run (gateway ledger, 25 OK calls) | 0.00417405 |
| Previous total for the day (integrated eval doc) | about 0.0102 |
| **Total OpenAI for the day** | **about 0.0144** |

## 7. Reproduce

Needs the golden-path stack (see `q4-platform-golden-path-2026-11-18.md`) and approval for a paid run. Run
`IntegratedPlatformEvalIT` with `EVAL_KNOWLEDGE=off`. Tag `platform-live` is excluded from the default build and CI.

## Appendix: raw report

Copied in full from `target/eval/report-platform-gateway-only-gpt-4o-mini.md` (`target/` is not committed). Note its
cost row shows $0 because the harness has no price for the model name `ai-gateway`; the cost above is from the ledger.

---

### Investigation eval: platform-gateway-only-gpt-4o-mini

Ablation: one call through ai-gateway (real provider gpt-4o-mini), knowledge pre-step OFF. Embeddings: test hashing model.

| Metric | Value |
|---|---|
| Cases | 28 |
| Jobs that finished with a proposal | 100.0% |
| Root-cause accuracy | 96.4% |
| Route accuracy | 85.7% |
| Cause-claiming answers whose citations verified | 96.0% |
| Required evidence cited (recall) | 100.0% |
| Latency p50 / p95 | 2853 ms / 4063 ms |
| Tokens (prompt + completion) | 15951 + 2969 |
| Cost | $0.000000 total, $0.000000 per case |

#### Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| clean | 100.0% |
| conflicting | 100.0% |
| data-entry | 100.0% |
| duplicate | 100.0% |
| fee | 100.0% |
| fx | 100.0% |
| hard | 50.0% |
| injection | 50.0% |
| material | 100.0% |
| missing | 100.0% |
| no-answer | 100.0% |
| non-usd | 100.0% |
| partial | 100.0% |
| timing | 100.0% |
| unrelated | 100.0% |
| vague | 100.0% |

#### Cases

| Case | Expected | Got | Route (expected / got) | Citations | Result |
|---|---|---|---|---|---|
| DAT-001 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| DAT-002 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| DAT-003 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L2_APPROVAL / L2_APPROVAL | valid | pass |
| DUP-001 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L2_APPROVAL / L2_APPROVAL | valid | pass |
| DUP-002 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| DUP-003 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L2_APPROVAL / L2_APPROVAL | valid | pass |
| FEE-001 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-002 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-003 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-004 | FEE_OR_CHARGE | FEE_OR_CHARGE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| FXR-001 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FXR-002 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FXR-003 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L2_APPROVAL / L1_APPROVAL | valid | miss |
| HRD-001 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-002 | UNKNOWN | TIMING_DIFFERENCE | MANUAL_REVIEW / MANUAL_REVIEW | invalid | miss |
| MIS-001 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| MIS-002 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| MIS-003 | MISSING_ENTRY | MISSING_ENTRY | L2_APPROVAL / L2_APPROVAL | valid | pass |
| PAR-001 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / L1_APPROVAL | valid | pass |
| PAR-002 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L2_APPROVAL / L1_APPROVAL | valid | miss |
| PAR-003 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-001 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-002 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| TIM-003 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-004 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L2_APPROVAL / MANUAL_REVIEW | valid | miss |
| UNK-001 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-002 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-003 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |

#### Misses

- **FXR-003**: root cause FX_RATE_DIFFERENCE (expected FX_RATE_DIFFERENCE), route L1_APPROVAL because STANDARD
- **HRD-002**: root cause TIMING_DIFFERENCE (expected UNKNOWN), route MANUAL_REVIEW because CITATIONS_INVALID
- **PAR-002**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route L1_APPROVAL because STANDARD
- **TIM-002**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **TIM-004**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
