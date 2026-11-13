package com.reconcileai.api.dto;

import com.reconcileai.domain.InvestigationJob;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Job status for polling. Deliberately excludes analyst note text and retrieved chunk ids. {@code reviewVerdict} and
 * {@code riskFlags} are filled only in the supervised modes.
 */
public record InvestigationJobResponse(
    UUID id, UUID breakItemId, String mode, String status, String stage, String initiatedBy,
    Integer contextTokenEstimate, String failureReason, UUID proposalId,
    Instant createdAt, Instant startedAt, Instant finishedAt, String reviewVerdict, List<String> riskFlags
) {
    public static InvestigationJobResponse from(InvestigationJob j) {
        return new InvestigationJobResponse(j.getId(), j.getBreakItemId(), j.getMode().name(), j.getStatus().name(), j.getStage().name(),
            j.getInitiatedBy(), j.getContextTokenEstimate(), j.getFailureReason(), j.getProposalId(),
            j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt(), j.getReviewVerdict(), j.getRiskFlags());
    }
}
