# Investigation eval: openai-gpt-4o-mini-agent-easy

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Metric | Value |
|---|---|
| Cases | 28 |
| Jobs that finished with a proposal | 100.0% |
| Root-cause accuracy | 100.0% |
| Route accuracy | 57.1% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 100.0% |
| Latency p50 / p95 | 11580 ms / 17110 ms |
| Tokens (prompt + completion) | 215008 + 5802 |
| Cost | $0.035732 total, $0.001276 per case |

## Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| clean | 100.0% |
| conflicting | 100.0% |
| data-entry | 100.0% |
| duplicate | 100.0% |
| fee | 100.0% |
| fx | 100.0% |
| hard | 100.0% |
| injection | 100.0% |
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
| DUP-001 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L2_APPROVAL / MANUAL_REVIEW | valid | miss |
| DUP-002 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / L1_APPROVAL | valid | pass |
| DUP-003 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L2_APPROVAL / MANUAL_REVIEW | valid | miss |
| FEE-001 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-002 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-003 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-004 | FEE_OR_CHARGE | FEE_OR_CHARGE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| FXR-001 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FXR-002 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FXR-003 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L2_APPROVAL / L1_APPROVAL | valid | miss |
| HRD-001 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-002 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| MIS-001 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| MIS-002 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| MIS-003 | MISSING_ENTRY | MISSING_ENTRY | L2_APPROVAL / MANUAL_REVIEW | valid | miss |
| PAR-001 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / L1_APPROVAL | valid | pass |
| PAR-002 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L2_APPROVAL / MANUAL_REVIEW | valid | miss |
| PAR-003 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| TIM-001 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| TIM-002 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| TIM-003 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| TIM-004 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L2_APPROVAL / MANUAL_REVIEW | valid | miss |
| UNK-001 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-002 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-003 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |

## Misses

- **DUP-001**: root cause DUPLICATE_ENTRY (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **DUP-003**: root cause DUPLICATE_ENTRY (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **FXR-003**: root cause FX_RATE_DIFFERENCE (expected FX_RATE_DIFFERENCE), route L1_APPROVAL because STANDARD
- **MIS-001**: root cause MISSING_ENTRY (expected MISSING_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **MIS-002**: root cause MISSING_ENTRY (expected MISSING_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **MIS-003**: root cause MISSING_ENTRY (expected MISSING_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **PAR-002**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because LOW_CONFIDENCE
- **PAR-003**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because LOW_CONFIDENCE
- **TIM-001**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **TIM-002**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **TIM-003**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **TIM-004**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
