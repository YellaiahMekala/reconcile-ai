# Phase 4G: supervised investigation modes on a real model (2026-11-13)

**Result: the decision rule written before the run was not met. Single call stays the default; the supervised modes stay off by default.**

- Model: gpt-4o-mini, temperature 0, the same model for every agent. Embeddings: the test hashing model (only the chat model is real).
- Cases: 28 easy plus 12 hard, labelled before the run. All results are from one run (run 3, see "How we got here").
- Cost of the whole run: well under 1 USD. Wall time 2016 s.
- Raw per-phase reports: [`4g-live-run3-2026-11-13/`](4g-live-run3-2026-11-13/). The partial first attempt is kept in [`4g-live-run1-2026-11-13/`](4g-live-run1-2026-11-13/).

## Decision rule (fixed in advance)

Recommend a supervised variant only if, on the hard set, it at least halves the wrong-cause answers that reach an approver compared with single call, at no more than 5x the single-call cost per case.

## Results (run 3, shared budget 12 tool calls / 24 model turns / 180 s)

| Mode | Set | Jobs ok | Root cause | Route | Wrong cause to approver | Reviewer challenges | Model calls/case | p50 | Cost/case |
|---|---|---|---|---|---|---|---|---|---|
| SINGLE_CALL | easy | 100% | 96.4% | 89.3% | 0 | - | 0.9 | 3.7 s | $0.000148 |
| SINGLE_CALL | hard | 100% | 100% | 75.0% | 0 | - | 1.0 | 3.7 s | $0.000176 |
| AGENT | easy | 100% | 92.9% | 53.6% | 0 | - | 6.6 | 10.6 s | $0.001148 |
| AGENT | hard | 100% | 100% | 33.3% | 0 | - | 4.2 | 8.5 s | $0.000785 |
| SUPERVISED | easy | 100% | 96.4% | 53.6% | 0 | 1 | 9.0 | 12.3 s | $0.001245 |
| SUPERVISED | hard | 100% | 100% | 41.7% | 0 | 2 | 7.8 | 12.2 s | $0.001196 |
| SUPERVISED_PLANNED | easy | 92.9% | 82.1% | 60.7% | 0 | 0 | 16.4 | 18.9 s | $0.001944 |
| SUPERVISED_PLANNED | hard | 91.7% | 50.0% | 25.0% | 0 | 0 | 15.7 | 22.7 s | $0.001856 |

Citations were valid in 100% of cause-claiming answers in every phase.

## Verdict

- Wrong causes reaching an approver on the hard set: 0 for single call and 0 for both supervised variants. There was nothing to halve.
- Cost per case against single call: SUPERVISED 7.9x, SUPERVISED_PLANNED 12.3x. The limit was 5x.
- Neither variant meets the rule. This is published as a negative result.

## What the run shows

- The reviewer works as designed. It challenged HRD-012 and HRD-013 (the unsupported-citation case) under SUPERVISED, and both went to manual review. The price is more manual work: the hard-set route score is 41.7% for SUPERVISED against 75.0% for single call.
- Single call already had the right root cause on all 12 hard cases, so accuracy did not separate the modes, and safety (no wrong cause to an approver) was equal in every mode.
- SUPERVISED_PLANNED, where the model decides the order, was the weakest: 50.0% root cause on the hard set. In 5 of 12 hard cases it ended with `UNKNOWN` and no reviewed draft, and one case hit `AGENT_TURN_LIMIT`. The reason the supervisor model ends early has not been investigated, so no cause is claimed.
- The route scores of agent and supervised modes are low mainly because of `LOW_CONFIDENCE` answers that go to manual review. That is the safe direction, but it means fewer cases are handled without a person.

## How we got here (honest account)

1. **Run 1** (budget 8 tool calls / 14 model turns / 120 s) wrote 7 of 8 phase reports and then crashed the harness. SUPERVISED finished only 66.7% of hard jobs and SUPERVISED_PLANNED 35.7% of easy jobs, all `AGENT_TURN_LIMIT`: gpt-4o-mini's analyst alone uses 6 to 8 model turns of the shared 14.
2. **Run 2** crashed after 50 minutes because the harness waited 30 s for a job that may legitimately run for up to 120 s.
3. Fixes between runs: HTTP/1.1 client for the `openai` profile (an earlier single-call run had truncated replies; whether this change or a transient problem was the cause is not proven); a malformed model-cited chunk id no longer crashes the harness; the harness waits 240 s per job; the live test fails only on provider failures; **the shared budget was raised to 12 / 24 / 180 s after seeing run-1 results**.
4. **Run 3** completed all eight phases. It is the run reported above. Run 1's reports are kept so the budget change is visible.

## Limits

- One model, one run, 40 author-written cases: not a benchmark and not a statistical claim.
- The reviewer is the same model family as the analyst, so correlated errors are possible.
- Embeddings were the test hashing model, so the dense retrieval leg is not semantic.
- Other models, or a larger budget, may give a different result. This run does not say supervised agents are never worth it, only that on this data and this model they did not meet the rule.
