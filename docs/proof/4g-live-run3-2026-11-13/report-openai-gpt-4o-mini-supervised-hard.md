# Investigation eval: openai-gpt-4o-mini-supervised-hard

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Metric | Value |
|---|---|
| Cases | 12 |
| Jobs that finished with a proposal | 100.0% |
| Root-cause accuracy | 100.0% |
| Route accuracy | 41.7% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 100.0% |
| Latency p50 / p95 | 12174 ms / 18354 ms |
| Tokens (prompt + completion) | 76059 + 4902 |
| Cost | $0.014350 total, $0.001196 per case |

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
| HRD-005 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-006 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-007 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-008 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-009 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-010 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-011 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-012 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L2_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-013 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-014 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |

## Misses

- **HRD-005**: root cause DUPLICATE_ENTRY (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-006**: root cause MISSING_ENTRY (expected MISSING_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-007**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-009**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-011**: root cause DUPLICATE_ENTRY (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-012**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because REVIEWER_CHALLENGE
- **HRD-013**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because REVIEWER_CHALLENGE
