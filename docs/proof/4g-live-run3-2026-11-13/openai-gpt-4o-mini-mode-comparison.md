# Investigation mode comparison: openai-gpt-4o-mini

Chat model: gpt-4o-mini (temperature 0), same model for every agent. Embeddings: test hashing model. Cases: 28 easy + 12 hard, labelled before the run.

| Mode | Set | Cases | Jobs ok | Root cause | Route | Wrong cause to approver | Reviewer challenges | Citations valid | Model calls/case | p50 ms | p95 ms | Cost/case USD |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SINGLE_CALL | easy | 28 | 100.0% | 96.4% | 89.3% | 0 | - | 100.0% | 0.9 | 3745 | 4724 | 0.000148 |
| SINGLE_CALL | hard | 12 | 100.0% | 100.0% | 75.0% | 0 | - | 100.0% | 1.0 | 3708 | 5732 | 0.000176 |
| AGENT | easy | 28 | 100.0% | 92.9% | 53.6% | 0 | - | 100.0% | 6.6 | 10621 | 12954 | 0.001148 |
| AGENT | hard | 12 | 100.0% | 100.0% | 33.3% | 0 | - | 100.0% | 4.2 | 8495 | 11602 | 0.000785 |
| SUPERVISED | easy | 28 | 100.0% | 96.4% | 53.6% | 0 | 1 | 100.0% | 9.0 | 12296 | 16737 | 0.001245 |
| SUPERVISED | hard | 12 | 100.0% | 100.0% | 41.7% | 0 | 2 | 100.0% | 7.8 | 12174 | 18354 | 0.001196 |
| SUPERVISED_PLANNED | easy | 28 | 92.9% | 82.1% | 60.7% | 0 | 0 | 100.0% | 16.4 | 18869 | 32682 | 0.001944 |
| SUPERVISED_PLANNED | hard | 12 | 91.7% | 50.0% | 25.0% | 0 | 0 | 100.0% | 15.7 | 22684 | 33811 | 0.001856 |

## Decision rule (fixed before the run)

Recommend a supervised variant only if, on the hard set, it at least halves the wrong-cause answers that reach an approver compared with single-call, at no more than 5x the single-call cost per case.

- **SUPERVISED**: does not meet the rule (hard-set wrong causes reaching an approver: 0 vs 0 for single-call (single-call had none, so there is nothing to halve); cost per case 7.9x of single-call)
- **SUPERVISED_PLANNED**: does not meet the rule (hard-set wrong causes reaching an approver: 0 vs 0 for single-call (single-call had none, so there is nothing to halve); cost per case 12.3x of single-call)

## Per-case results on the hard set

| Case | Tags | Expected | SINGLE_CALL | AGENT | SUPERVISED | SUPERVISED_PLANNED | 
|---|---|---|---|---|---|---|
| HRD-003 | conflicting, hard | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / L1_APPROVAL (AGREE) | FEE_OR_CHARGE / L1_APPROVAL (AGREE) | 
| HRD-004 | conflicting, hard | FX_RATE_DIFFERENCE / L1_APPROVAL | FX_RATE_DIFFERENCE / L1_APPROVAL | FX_RATE_DIFFERENCE / L1_APPROVAL | FX_RATE_DIFFERENCE / L1_APPROVAL (AGREE) | UNKNOWN / MANUAL_REVIEW (NOT_RUN) | 
| HRD-005 | conflicting, hard | DUPLICATE_ENTRY / L1_APPROVAL | DUPLICATE_ENTRY / L1_APPROVAL | DUPLICATE_ENTRY / MANUAL_REVIEW | DUPLICATE_ENTRY / MANUAL_REVIEW (AGREE) | DUPLICATE_ENTRY / MANUAL_REVIEW (AGREE) | 
| HRD-006 | conflicting, hard | MISSING_ENTRY / L1_APPROVAL | MISSING_ENTRY / L1_APPROVAL | MISSING_ENTRY / MANUAL_REVIEW | MISSING_ENTRY / MANUAL_REVIEW (AGREE) | UNKNOWN / MANUAL_REVIEW (NOT_RUN) | 
| HRD-007 | misleading-note, hard | TIMING_DIFFERENCE / L1_APPROVAL | TIMING_DIFFERENCE / MANUAL_REVIEW | TIMING_DIFFERENCE / MANUAL_REVIEW | TIMING_DIFFERENCE / MANUAL_REVIEW (AGREE) | TIMING_DIFFERENCE / MANUAL_REVIEW (AGREE) | 
| HRD-008 | misleading-note, hard | DATA_ENTRY_ERROR / L1_APPROVAL | DATA_ENTRY_ERROR / L1_APPROVAL | DATA_ENTRY_ERROR / MANUAL_REVIEW | DATA_ENTRY_ERROR / L1_APPROVAL (AGREE) | DATA_ENTRY_ERROR / L1_APPROVAL (AGREE) | 
| HRD-009 | misleading-note, hard | PARTIAL_SETTLEMENT / L1_APPROVAL | PARTIAL_SETTLEMENT / MANUAL_REVIEW | PARTIAL_SETTLEMENT / MANUAL_REVIEW | PARTIAL_SETTLEMENT / MANUAL_REVIEW (AGREE) | FAILED AGENT_TURN_LIMIT | 
| HRD-010 | two-causes, hard | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / MANUAL_REVIEW | FEE_OR_CHARGE / L1_APPROVAL | FEE_OR_CHARGE / L1_APPROVAL (AGREE) | FEE_OR_CHARGE / L1_APPROVAL (AGREE) | 
| HRD-011 | two-causes, hard | DUPLICATE_ENTRY / L1_APPROVAL | DUPLICATE_ENTRY / L1_APPROVAL | DUPLICATE_ENTRY / MANUAL_REVIEW | DUPLICATE_ENTRY / MANUAL_REVIEW (AGREE) | UNKNOWN / MANUAL_REVIEW (NOT_RUN) | 
| HRD-012 | two-causes, hard, material | TIMING_DIFFERENCE / L2_APPROVAL | TIMING_DIFFERENCE / L2_APPROVAL | TIMING_DIFFERENCE / MANUAL_REVIEW | TIMING_DIFFERENCE / MANUAL_REVIEW (CHALLENGE) | UNKNOWN / MANUAL_REVIEW (NOT_RUN) | 
| HRD-013 | unsupported-citation, hard | PARTIAL_SETTLEMENT / L1_APPROVAL | PARTIAL_SETTLEMENT / L1_APPROVAL | PARTIAL_SETTLEMENT / L1_APPROVAL | PARTIAL_SETTLEMENT / MANUAL_REVIEW (CHALLENGE) | PARTIAL_SETTLEMENT / MANUAL_REVIEW (AGREE) | 
| HRD-014 | unsupported-citation, hard | DATA_ENTRY_ERROR / L1_APPROVAL | DATA_ENTRY_ERROR / L1_APPROVAL | DATA_ENTRY_ERROR / MANUAL_REVIEW | DATA_ENTRY_ERROR / L1_APPROVAL (AGREE) | UNKNOWN / MANUAL_REVIEW (NOT_RUN) | 
