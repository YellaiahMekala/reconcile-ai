# Investigation eval: openai-gpt-4o-mini-single-call-hard

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Metric | Value |
|---|---|
| Cases | 12 |
| Jobs that finished with a proposal | 100.0% |
| Root-cause accuracy | 100.0% |
| Route accuracy | 75.0% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 100.0% |
| Latency p50 / p95 | 3708 ms / 5732 ms |
| Tokens (prompt + completion) | 7847 + 1553 |
| Cost | $0.002109 total, $0.000176 per case |

## Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| conflicting | 100.0% |
| hard | 100.0% |
| material | 100.0% |
| misleading-note | 100.0% |
| two-causes | 100.0% |
| unsupported-citation | 100.0% |

## Cases

| Case | Expected | Got | Route (expected / got) | Citations | Result |
|---|---|---|---|---|---|
| HRD-003 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-004 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-005 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-006 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-007 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-008 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-009 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-010 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-011 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-012 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| HRD-013 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-014 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |

## Misses

- **HRD-007**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-009**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-010**: root cause FEE_OR_CHARGE (expected FEE_OR_CHARGE), route MANUAL_REVIEW because LOW_CONFIDENCE
