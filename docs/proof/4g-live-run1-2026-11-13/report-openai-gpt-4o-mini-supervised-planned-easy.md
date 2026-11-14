# Investigation eval: openai-gpt-4o-mini-supervised-planned-easy

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Metric | Value |
|---|---|
| Cases | 28 |
| Jobs that finished with a proposal | 35.7% |
| Root-cause accuracy | 35.7% |
| Route accuracy | 25.0% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 41.7% |
| Latency p50 / p95 | 16393 ms / 20708 ms |
| Tokens (prompt + completion) | 236893 + 11054 |
| Cost | $0.042166 total, $0.001506 per case |

## Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| clean | 46.7% |
| conflicting | 100.0% |
| data-entry | 33.3% |
| duplicate | 0.0% |
| fee | 50.0% |
| fx | 66.7% |
| hard | 50.0% |
| injection | 0.0% |
| material | 14.3% |
| missing | 33.3% |
| no-answer | 0.0% |
| non-usd | 100.0% |
| partial | 66.7% |
| timing | 25.0% |
| unrelated | 0.0% |
| vague | 0.0% |

## Cases

| Case | Expected | Got | Route (expected / got) | Citations | Result |
|---|---|---|---|---|---|
| DAT-001 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| DAT-002 | DATA_ENTRY_ERROR | no proposal | L1_APPROVAL / - | n/a | miss |
| DAT-003 | DATA_ENTRY_ERROR | no proposal | L2_APPROVAL / - | n/a | miss |
| DUP-001 | DUPLICATE_ENTRY | no proposal | L2_APPROVAL / - | n/a | miss |
| DUP-002 | DUPLICATE_ENTRY | no proposal | L1_APPROVAL / - | n/a | miss |
| DUP-003 | DUPLICATE_ENTRY | no proposal | L2_APPROVAL / - | n/a | miss |
| FEE-001 | FEE_OR_CHARGE | no proposal | L1_APPROVAL / - | n/a | miss |
| FEE-002 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-003 | FEE_OR_CHARGE | no proposal | L1_APPROVAL / - | n/a | miss |
| FEE-004 | FEE_OR_CHARGE | FEE_OR_CHARGE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| FXR-001 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FXR-002 | FX_RATE_DIFFERENCE | FX_RATE_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FXR-003 | FX_RATE_DIFFERENCE | no proposal | L2_APPROVAL / - | n/a | miss |
| HRD-001 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-002 | UNKNOWN | no proposal | MANUAL_REVIEW / - | n/a | miss |
| MIS-001 | MISSING_ENTRY | no proposal | L1_APPROVAL / - | n/a | miss |
| MIS-002 | MISSING_ENTRY | MISSING_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| MIS-003 | MISSING_ENTRY | no proposal | L2_APPROVAL / - | n/a | miss |
| PAR-001 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / L1_APPROVAL | valid | pass |
| PAR-002 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L2_APPROVAL / L1_APPROVAL | valid | miss |
| PAR-003 | PARTIAL_SETTLEMENT | no proposal | L1_APPROVAL / - | n/a | miss |
| TIM-001 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| TIM-002 | TIMING_DIFFERENCE | no proposal | L1_APPROVAL / - | n/a | miss |
| TIM-003 | TIMING_DIFFERENCE | no proposal | L1_APPROVAL / - | n/a | miss |
| TIM-004 | TIMING_DIFFERENCE | no proposal | L2_APPROVAL / - | n/a | miss |
| UNK-001 | UNKNOWN | no proposal | MANUAL_REVIEW / - | n/a | miss |
| UNK-002 | UNKNOWN | no proposal | MANUAL_REVIEW / - | n/a | miss |
| UNK-003 | UNKNOWN | no proposal | MANUAL_REVIEW / - | n/a | miss |

## Misses

- **DAT-002**: job failed (AGENT_TURN_LIMIT)
- **DAT-003**: job failed (AGENT_TURN_LIMIT)
- **DUP-001**: job failed (AGENT_TURN_LIMIT)
- **DUP-002**: job failed (AGENT_TURN_LIMIT)
- **DUP-003**: job failed (AGENT_TURN_LIMIT)
- **FEE-001**: job failed (AGENT_TURN_LIMIT)
- **FEE-003**: job failed (AGENT_TURN_LIMIT)
- **FXR-003**: job failed (AGENT_TURN_LIMIT)
- **HRD-002**: job failed (AGENT_TURN_LIMIT)
- **MIS-001**: job failed (AGENT_TURN_LIMIT)
- **MIS-002**: root cause MISSING_ENTRY (expected MISSING_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **MIS-003**: job failed (AGENT_TURN_LIMIT)
- **PAR-002**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route L1_APPROVAL because STANDARD
- **PAR-003**: job failed (AGENT_TURN_LIMIT)
- **TIM-001**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **TIM-002**: job failed (AGENT_TURN_LIMIT)
- **TIM-003**: job failed (AGENT_TURN_LIMIT)
- **TIM-004**: job failed (AGENT_TURN_LIMIT)
- **UNK-001**: job failed (AGENT_TURN_LIMIT)
- **UNK-002**: job failed (AGENT_TURN_LIMIT)
- **UNK-003**: job failed (AGENT_TURN_LIMIT)
