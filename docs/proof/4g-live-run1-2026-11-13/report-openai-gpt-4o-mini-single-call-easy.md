# Investigation eval: openai-gpt-4o-mini-single-call-easy

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Metric | Value |
|---|---|
| Cases | 28 |
| Jobs that finished with a proposal | 100.0% |
| Root-cause accuracy | 96.4% |
| Route accuracy | 92.9% |
| Cause-claiming answers whose citations verified | 96.0% |
| Required evidence cited (recall) | 100.0% |
| Latency p50 / p95 | 4904 ms / 5882 ms |
| Tokens (prompt + completion) | 15926 + 2957 |
| Cost | $0.004163 total, $0.000149 per case |

## Root-cause accuracy by tag

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

## Cases

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
| TIM-002 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-003 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-004 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| UNK-001 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-002 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-003 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |

## Misses

- **FXR-003**: root cause FX_RATE_DIFFERENCE (expected FX_RATE_DIFFERENCE), route L1_APPROVAL because STANDARD
- **HRD-002**: root cause TIMING_DIFFERENCE (expected UNKNOWN), route MANUAL_REVIEW because CITATIONS_INVALID
- **PAR-002**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route L1_APPROVAL because STANDARD
