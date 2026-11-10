# Investigation eval: bedrock-fake

Chat: fake Bedrock Converse endpoint answering with the mock-llm rules, through the real Bedrock adapter. Measures adapter fidelity, not model quality.

| Metric | Value |
|---|---|
| Cases | 28 |
| Jobs that finished with a proposal | 100.0% |
| Root-cause accuracy | 42.9% |
| Route accuracy | 42.9% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 33.3% |
| Latency p50 / p95 | 288 ms / 324 ms |
| Tokens (prompt + completion) | 14525 + 1510 |
| Cost | $0.000000 total, $0.000000 per case |

## Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| clean | 40.0% |
| conflicting | 0.0% |
| data-entry | 0.0% |
| duplicate | 0.0% |
| fee | 100.0% |
| fx | 0.0% |
| hard | 50.0% |
| injection | 100.0% |
| material | 14.3% |
| missing | 0.0% |
| no-answer | 100.0% |
| non-usd | 100.0% |
| partial | 0.0% |
| timing | 100.0% |
| unrelated | 100.0% |
| vague | 100.0% |

## Cases

| Case | Expected | Got | Route (expected / got) | Citations | Result |
|---|---|---|---|---|---|
| DAT-001 | DATA_ENTRY_ERROR | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| DAT-002 | DATA_ENTRY_ERROR | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| DAT-003 | DATA_ENTRY_ERROR | UNKNOWN | L2_APPROVAL / MANUAL_REVIEW | n/a | miss |
| DUP-001 | DUPLICATE_ENTRY | UNKNOWN | L2_APPROVAL / MANUAL_REVIEW | n/a | miss |
| DUP-002 | DUPLICATE_ENTRY | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| DUP-003 | DUPLICATE_ENTRY | UNKNOWN | L2_APPROVAL / MANUAL_REVIEW | n/a | miss |
| FEE-001 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-002 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-003 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| FEE-004 | FEE_OR_CHARGE | FEE_OR_CHARGE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| FXR-001 | FX_RATE_DIFFERENCE | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| FXR-002 | FX_RATE_DIFFERENCE | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| FXR-003 | FX_RATE_DIFFERENCE | UNKNOWN | L2_APPROVAL / MANUAL_REVIEW | n/a | miss |
| HRD-001 | FEE_OR_CHARGE | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| HRD-002 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| MIS-001 | MISSING_ENTRY | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| MIS-002 | MISSING_ENTRY | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| MIS-003 | MISSING_ENTRY | UNKNOWN | L2_APPROVAL / MANUAL_REVIEW | n/a | miss |
| PAR-001 | PARTIAL_SETTLEMENT | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| PAR-002 | PARTIAL_SETTLEMENT | UNKNOWN | L2_APPROVAL / MANUAL_REVIEW | n/a | miss |
| PAR-003 | PARTIAL_SETTLEMENT | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| TIM-001 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-002 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-003 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| TIM-004 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L2_APPROVAL / L2_APPROVAL | valid | pass |
| UNK-001 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-002 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |
| UNK-003 | UNKNOWN | UNKNOWN | MANUAL_REVIEW / MANUAL_REVIEW | n/a | pass |

## Misses

- **DAT-001**: root cause UNKNOWN (expected DATA_ENTRY_ERROR), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [dat-001-keying-note.txt]
- **DAT-002**: root cause UNKNOWN (expected DATA_ENTRY_ERROR), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [dat-002-review-comment.txt]
- **DAT-003**: root cause UNKNOWN (expected DATA_ENTRY_ERROR), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [dat-003-correction-ticket.txt]
- **DUP-001**: root cause UNKNOWN (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [dup-001-ledger-extract.txt]
- **DUP-002**: root cause UNKNOWN (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [dup-002-import-log.txt]
- **DUP-003**: root cause UNKNOWN (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [dup-003-ar-listing.txt]
- **FXR-001**: root cause UNKNOWN (expected FX_RATE_DIFFERENCE), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [fxr-001-fx-confirmation.txt]
- **FXR-002**: root cause UNKNOWN (expected FX_RATE_DIFFERENCE), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [fxr-002-rate-sheet.txt]
- **FXR-003**: root cause UNKNOWN (expected FX_RATE_DIFFERENCE), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [fxr-003-conversion-ticket.txt]
- **HRD-001**: root cause UNKNOWN (expected FEE_OR_CHARGE), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [hrd-001-bank-charge.txt]
- **MIS-001**: root cause UNKNOWN (expected MISSING_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [mis-001-bank-statement.txt]
- **MIS-002**: root cause UNKNOWN (expected MISSING_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [mis-002-bank-extract.txt]
- **MIS-003**: root cause UNKNOWN (expected MISSING_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [mis-003-recon-worksheet.txt]
- **PAR-001**: root cause UNKNOWN (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [par-001-remittance.txt]
- **PAR-002**: root cause UNKNOWN (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [par-002-customer-letter.txt]
- **PAR-003**: root cause UNKNOWN (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [par-003-payment-plan.txt]
