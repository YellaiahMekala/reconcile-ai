package com.reconcileai.api.dto;

import com.reconcileai.domain.ApprovalTask;

import java.time.Instant;
import java.util.UUID;

public record ApprovalTaskResponse(UUID id, UUID proposalId, UUID breakItemId, String requiredRole, String status,
                                   String makerSubject, String decidedBy, String decisionComment,
                                   Instant decidedAt, Instant createdAt) {

    public static ApprovalTaskResponse from(ApprovalTask t) {
        return new ApprovalTaskResponse(t.getId(), t.getProposalId(), t.getBreakItemId(), t.getRequiredRole(),
            t.getStatus().name(), t.getMakerSubject(), t.getDecidedBy(), t.getDecisionComment(), t.getDecidedAt(),
            t.getCreatedAt());
    }
}
