# Investigation eval: openai-gpt-4o-mini-supervised-planned-hard

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Metric | Value |
|---|---|
| Cases | 12 |
| Jobs that finished with a proposal | 91.7% |
| Root-cause accuracy | 50.0% |
| Route accuracy | 25.0% |
| Cause-claiming answers whose citations verified | 100.0% |
| Required evidence cited (recall) | 50.0% |
| Latency p50 / p95 | 22684 ms / 33811 ms |
| Tokens (prompt + completion) | 117362 + 7770 |
| Cost | $0.022266 total, $0.001856 per case |

## Root-cause accuracy by tag

| Tag | Accuracy |
|---|---|
| conflicting | 50.0% |
| hard | 50.0% |
| material | 0.0% |
| misleading-note | 66.7% |
| two-causes | 33.3% |
| unsupported-citation | 50.0% |

## Cases

| Case | Expected | Got | Route (expected / got) | Citations | Result |
|---|---|---|---|---|---|
| HRD-003 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-004 | FX_RATE_DIFFERENCE | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| HRD-005 | DUPLICATE_ENTRY | DUPLICATE_ENTRY | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-006 | MISSING_ENTRY | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| HRD-007 | TIMING_DIFFERENCE | TIMING_DIFFERENCE | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-008 | DATA_ENTRY_ERROR | DATA_ENTRY_ERROR | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-009 | PARTIAL_SETTLEMENT | no proposal | L1_APPROVAL / - | n/a | miss |
| HRD-010 | FEE_OR_CHARGE | FEE_OR_CHARGE | L1_APPROVAL / L1_APPROVAL | valid | pass |
| HRD-011 | DUPLICATE_ENTRY | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |
| HRD-012 | TIMING_DIFFERENCE | UNKNOWN | L2_APPROVAL / MANUAL_REVIEW | n/a | miss |
| HRD-013 | PARTIAL_SETTLEMENT | PARTIAL_SETTLEMENT | L1_APPROVAL / MANUAL_REVIEW | valid | miss |
| HRD-014 | DATA_ENTRY_ERROR | UNKNOWN | L1_APPROVAL / MANUAL_REVIEW | n/a | miss |

## Misses

- **HRD-004**: root cause UNKNOWN (expected FX_RATE_DIFFERENCE), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [hrd-004-fx-confirmation.txt]
- **HRD-005**: root cause DUPLICATE_ENTRY (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-006**: root cause UNKNOWN (expected MISSING_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [hrd-006-bank-extract.txt]
- **HRD-007**: root cause TIMING_DIFFERENCE (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-009**: job failed (AGENT_TURN_LIMIT)
- **HRD-011**: root cause UNKNOWN (expected DUPLICATE_ENTRY), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [hrd-011-import-log.txt]
- **HRD-012**: root cause UNKNOWN (expected TIMING_DIFFERENCE), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [hrd-012-bank-extract.txt]
- **HRD-013**: root cause PARTIAL_SETTLEMENT (expected PARTIAL_SETTLEMENT), route MANUAL_REVIEW because LOW_CONFIDENCE
- **HRD-014**: root cause UNKNOWN (expected DATA_ENTRY_ERROR), route MANUAL_REVIEW because UNKNOWN_ROOT_CAUSE, did not cite [hrd-014-correction-ticket.txt]
