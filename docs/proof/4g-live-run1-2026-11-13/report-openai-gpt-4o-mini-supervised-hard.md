# Investigation eval: openai-gpt-4o-mini-supervised-hard

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Metric | Value |
|---|---|
| Cases | 12 |
| Jobs that finished with a proposal | 66.7% |
| Root-cause accuracy | 66.7% |
| Route accuracy | 25.0% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 66.7% |
| Latency p50 / p95 | 11187 ms / 18126 ms |
| Tokens (prompt + completion) | 77439 + 5140 |
| Cost | $0.014700 total, $0.001225 per case |

## Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| conflicting | 50.0% |
| hard | 66.7% |
| material | 0.0% |
| misleading-note | 100.0% |
| two-causes | 33.3% |
| unsupported-citation | 100.0% |

## Cases

| Case | Expected | Got | Route (expected / got) | Citations | Result |
|---|---|---|---|---|---|
| HRD-003 | FEE_OR_CHARGE | no proposal | L1_APPROVAL / - | n/a | miss |
| HRD-004 | FX_RATE_DIFFERENCE | no proposal | L1_APPROVAL / - | n/a | miss |
| HRD-005 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-006 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-007 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-008 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-009 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-010 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-011 | DUPLICATE_ENTRY | no proposal | L1_APPROVAL / - | n/a | miss |
| HRD-012 | TIMING_DIFFERENCE | no proposal | L2_APPROVAL / - | n/a | miss |
| HRD-013 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-014 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |

## Misses

- **HRD-003**: job failed (AGENT_TURN_LIMIT)
- **HRD-004**: job failed (AGENT_TURN_LIMIT)
- **HRD-005**: root cause DUPLICATE_ENTRY (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-006**: root cause MISSING_ENTRY (expected MISSING_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-007**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-009**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-011**: job failed (AGENT_TURN_LIMIT)
- **HRD-012**: job failed (AGENT_TURN_LIMIT)
- **HRD-013**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because REVIEWER_CHALLENGE
