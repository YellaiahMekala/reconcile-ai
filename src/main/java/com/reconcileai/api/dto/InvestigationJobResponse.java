package com.reconcileai.api.dto;

import com.reconcileai.domain.InvestigationJob;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Job status for polling. Deliberately excludes analyst note text and retrieved chunk ids. {@code reviewVerdict} and
 * {@code riskFlags} are filled only in the supervised modes. {@code outcomeUnknown} marks a gateway call whose result
 * was never confirmed; {@code reconciledBy}, {@code reconcileRef} and the two {@code ledger*} fields describe how it
 * was cleared (the ledger fields only when the reference was an invocation id that reconcile-ai checked itself).
 */
public record InvestigationJobResponse(
    UUID id, UUID breakItemId, String mode, String status, String stage, String initiatedBy,
    Integer contextTokenEstimate, String failureReason, UUID proposalId,
    Instant createdAt, Instant startedAt, Instant finishedAt, String reviewVerdict, List<String> riskFlags,
    boolean outcomeUnknown, Instant reconciledAt, String reconciledBy, String reconcileRef,
    String ledgerExecutionCertainty, String ledgerStatus
) {
    public static InvestigationJobResponse from(InvestigationJob j) {
        return new InvestigationJobResponse(j.getId(), j.getBreakItemId(), j.getMode().name(), j.getStatus().name(), j.getStage().name(),
            j.getInitiatedBy(), j.getContextTokenEstimate(), j.getFailureReason(), j.getProposalId(),
            j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt(), j.getReviewVerdict(), j.getRiskFlags(),
            j.isOutcomeUnknown(), j.getReconciledAt(), j.getReconciledBy(), j.getReconcileRef(),
            j.getLedgerExecutionCertainty(), j.getLedgerStatus());
    }
}
