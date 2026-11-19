// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A human checker's work item for one proposal routed to L1 or L2.
 *
 * <p>{@code requiredRole} is stored as a plain string (APPROVER_L1 / APPROVER_L2) so the domain does not depend on the
 * security package. {@code makerSubject} is copied from {@link ResolutionProposal#getMakerSubject()}: the
 * maker-checker comparison is always "checker sub vs MAKER sub", never "vs decidedBy" (which is null until decided).
 */
@Entity
@Table(name = "approval_task")
public class ApprovalTask {

    public static final String L1 = "APPROVER_L1";
    public static final String L2 = "APPROVER_L2";

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

    @Column(name = "proposal_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID proposalId;

    @Column(name = "break_item_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID breakItemId;

    @Column(name = "required_role", nullable = false, updatable = false, length = 32)
    private String requiredRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApprovalStatus status;

    @Column(name = "maker_subject", nullable = false, updatable = false, length = 128)
    private String makerSubject;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @Column(name = "decision_comment", length = 1000)
    private String decisionComment;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Two checkers clicking at once: the second commit fails with an optimistic-lock conflict (409). */
    @Version
    @Column(nullable = false)
    private long version;

    protected ApprovalTask() { // JPA
    }

    public ApprovalTask(ResolutionProposal proposal, RoutingDecision route) {
        this.tenantId = proposal.getTenantId();
        this.proposalId = proposal.getId();
        this.breakItemId = proposal.getBreakItemId();
        this.requiredRole = switch (route) {
            case L1_APPROVAL -> L1;
            case L2_APPROVAL -> L2;
            default -> throw new IllegalArgumentException("No approval task for route " + route);
        };
        this.makerSubject = proposal.getMakerSubject();
        this.status = ApprovalStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void decide(ApprovalDecision decision, String checkerSubject, String comment) {
        if (status != ApprovalStatus.OPEN) {
            throw new IllegalStateException("APPROVAL_ALREADY_DECIDED");
        }
        if (makerSubject.equals(checkerSubject)) {
            throw new IllegalStateException("MAKER_CHECKER_VIOLATION"); // service checks first; this is the last line
        }
        this.status = decision == ApprovalDecision.APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        this.decidedBy = checkerSubject;
        this.decisionComment = comment;
        this.decidedAt = Instant.now();
    }

    public boolean isL2() { return L2.equals(requiredRole); }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getProposalId() { return proposalId; }
    public UUID getBreakItemId() { return breakItemId; }
    public String getRequiredRole() { return requiredRole; }
    public ApprovalStatus getStatus() { return status; }
    public String getMakerSubject() { return makerSubject; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionComment() { return decisionComment; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
