# Investigation eval: q3-7-hard-rero-gateway-gpt-4o-mini

Q3.7 keep/cut run, engine RERO, knowledge OFF, model only through ai-gateway (gpt-4o-mini, temperature 0.0). Embeddings: test hashing model.

| Metric | Value |
|---|---|
| Cases | 12 |
| Jobs that finished with a proposal | 100.0% |
| Root-cause accuracy | 83.3% |
| Route accuracy | 66.7% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 100.0% |
| Latency p50 / p95 | 4536 ms / 5770 ms |
| Tokens (prompt + completion) | 0 + 0 |
| Cost | $0.000000 total, $0.000000 per case |

## Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| conflicting | 100.0% |
| hard | 83.3% |
| material | 100.0% |
| misleading-note | 33.3% |
| two-causes | 100.0% |
| unsupported-citation | 100.0% |

## Cases

| Case | Expected | Got | Route (expected / got) | Citations | Result |
|---|---|---|---|---|---|
| HRD-003 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-004 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-005 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-006 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-007 | TIMING_DIFFERENCE | PARTIAL_SETTLEMENT | L1_APPROVAL / L1_APPROVAL | valid | miss |
| HRD-008 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-009 | PARTIAL_SETTLEMENT | DUPLICATE_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-010 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-011 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-012 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| HRD-013 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-014 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |

## Misses

- **HRD-006**: root cause MISSING_ENTRY (expected MISSING_ENTRY), route MANUAL_REVIEW because REVIEWER_CHALLENGE
- **HRD-007**: root cause PARTIAL_SETTLEMENT (expected TIMING_DIFFERENCE), route L1_APPROVAL because STANDARD
- **HRD-009**: root cause DUPLICATE_ENTRY (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because REVIEWER_CHALLENGE
- **HRD-010**: root cause FEE_OR_CHARGE (expected FEE_OR_CHARGE), route MANUAL_REVIEW because REVIEWER_CHALLENGE
- **HRD-011**: root cause DUPLICATE_ENTRY (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because REVIEWER_CHALLENGE
