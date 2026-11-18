# Q4.3: integrated platform evaluation, 28 cases (2026-11-18)

Status: **run 2026-11-18, local, uncommitted at the time of writing.** Paid run (OpenAI `gpt-4o-mini`), same 28 labelled
cases for both columns, test hashing embeddings in both (the dense leg is not semantic). Synthetic author-written cases,
not a benchmark.

- **Baseline:** `OpenAiInvestigationEvalIT`, direct OpenAI, no knowledge.
- **Integrated:** `IntegratedPlatformEvalIT` (tag `platform-live`, excluded from the default build): EKA pre-step against
  the real edge (tenant-a procedures) plus one call through ai-gateway on its real-provider profile.

Reports: `target/eval/report-openai-gpt-4o-mini.md` and `target/eval/report-platform-gateway-eka-gpt-4o-mini.md`.

## 1. Side by side

| Metric | Baseline (direct) | Integrated (EKA + gateway) |
|---|---|---|
| Cases | 28 | 28 |
| Root cause correct | 27/28 | 27/28 |
| Route correct | 24/28 (85.7%) | 19/28 (67.9%) |
| Cause-claiming answers whose citations verified | 100% | 100% |
| Required evidence cited (recall) | 100% | 100% |
| Latency p50 / p95 | 2634 ms / 3555 ms | 2779 ms / 3825 ms |
| Tokens in / out | 15964 / 2952 | 24414 / 2989 |
| Cost | about 0.0042 USD (list-price estimate by the harness) | 0 in the harness report (see below) |

The harness shows 0 cost for the integrated run because it has no price for the model name `ai-gateway`. The real cost
comes from the gateway ledger: 25 provider calls (the 3 no-evidence cases never call the model). The ledger total for all
of 2026-11-18's live work is 28 OK calls = 0.006053 USD, which includes the golden path (2 calls) and Q2.7 (1 call).

## 2. The five route differences

All five moved in the cautious direction. The root cause was the same in every one.

| Case | Expected route | Baseline | Integrated |
|---|---|---|---|
| MIS-001 | L1_APPROVAL | L1_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| MIS-002 | L1_APPROVAL | L1_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| MIS-003 | L2_APPROVAL | L2_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| TIM-001 | L1_APPROVAL | L1_APPROVAL | MANUAL_REVIEW (LOW_CONFIDENCE) |
| HRD-002 | MANUAL_REVIEW | MANUAL_REVIEW | L1_APPROVAL |

Four cases went to manual review where the baseline matched the expected route. HRD-002 went to L1 approval where
manual review was expected; the root cause there (TIMING_DIFFERENCE, expected UNKNOWN) was already wrong in both runs.
None of the five turned a wrong cause into an approval.

## 3. Honest reading

- **Root cause did not change** (27/28 in both). Citations stayed valid and recall stayed at 100%.
- **Route accuracy fell by 5 cases** (24 to 19). With n = 28, a 5-case difference is not statistically significant, so
  read these as counts, not as a measured effect size. One run per column.
- **The direction is cautious.** The integrated path sent more breaks to a person, not fewer.
- **Cause: separated by a follow-up ablation.** Gateway on with knowledge off returns 24/28, equal to the baseline, so
  the drop is attributable to the knowledge pre-step on this corpus, not the gateway route. See
  `q4-3-knowledge-ablation-2026-11-18.md`.
- The integrated run used about 8450 more prompt tokens than the baseline (24414 vs 15964). The cause was not
  measured.

## 4. Spend for the day

| Item | USD |
|---|---|
| Gateway ledger, all live work (28 OK calls: eval 25, golden path 2, Q2.7 1) | 0.006053 |
| Baseline run (harness list-price estimate) | about 0.0042 |
| Embeddings | negligible |
| **Total OpenAI (this run's day, before the ablation)** | **about 0.0102** |

The ablation run added 0.00417405 USD (about 0.0144 USD total for the day).

## 5. Reproduce

Needs the golden-path stack (see `q4-platform-golden-path-2026-11-18.md`) with tenant-a procedures loaded, and approval
for a paid run. The tag `platform-live` is excluded from the default build and from CI.
