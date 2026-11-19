// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * An AI-generated, human-reviewable proposal for resolving a break.
 *
 * <p>Created ONLY by {@code ProposalService.persistProposal()}. Creating a proposal never resolves anything:
 * persistProposal() ≠ commitResolution() (locked invariant). The break reaches RESOLVED only through
 * ResolutionCommitService after a checker's decision.
 *
 * <p>{@code varianceAmount} is a snapshot of |ledger − external| at proposal time, so an approver sees exactly
 * the materiality the policy gate used.
 */
@Entity
@Table(name = "resolution_proposal")
public class ResolutionProposal {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

    @Column(name = "break_item_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID breakItemId;

    @Column(name = "job_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "root_cause", nullable = false, length = 32)
    private RootCauseCategory rootCause;

    @Column(nullable = false, columnDefinition = "text")
    private String explanation;

    @Column(name = "recommended_action", nullable = false, columnDefinition = "text")
    private String recommendedAction;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "cited_chunk_ids", nullable = false, columnDefinition = "text")
    private String citedChunkIds;

    @Column(name = "citations_valid", nullable = false)
    private boolean citationsValid;

    @Column(name = "invalid_citations", columnDefinition = "text")
    private String invalidCitations;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoutingDecision route;

    @Column(name = "route_reason", nullable = false, length = 64)
    private String routeReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProposalStatus status;

    /** The analyst who started the investigation. Maker-checker compares approver sub against THIS (never decidedBy). */
    @Column(name = "maker_subject", nullable = false, updatable = false, length = 128)
    private String makerSubject;

    @Column(name = "variance_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal varianceAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ResolutionProposal() { // JPA
    }

    public ResolutionProposal(String tenantId, UUID breakItemId, UUID jobId, RootCauseCategory rootCause,
                              String explanation, String recommendedAction, BigDecimal confidence,
                              List<String> citedChunkIds, boolean citationsValid, List<String> invalidCitations,
                              RoutingDecision route, String routeReason, String makerSubject, Money variance) {
        this.tenantId = tenantId;
        this.breakItemId = breakItemId;
        this.jobId = jobId;
        this.rootCause = rootCause;
        this.explanation = explanation;
        this.recommendedAction = recommendedAction;
        this.confidence = confidence;
        this.citedChunkIds = String.join(",", citedChunkIds);
        this.citationsValid = citationsValid;
        this.invalidCitations = invalidCitations.isEmpty() ? null : String.join(",", invalidCitations);
        this.route = route;
        this.routeReason = routeReason;
        this.makerSubject = makerSubject;
        this.varianceAmount = variance.amount();
        this.currencyCode = variance.currency().getCurrencyCode();
        this.status = route == RoutingDecision.MANUAL_REVIEW ? ProposalStatus.MANUAL_REVIEW : ProposalStatus.PENDING_APPROVAL;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** Called ONLY by ResolutionCommitService (approve) and ApprovalService's reject path. */
    public void markApproved() {
        requirePending();
        this.status = ProposalStatus.APPROVED;
    }

    public void markRejected() {
        requirePending();
        this.status = ProposalStatus.REJECTED;
    }

    private void requirePending() {
        if (status != ProposalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("PROPOSAL_NOT_PENDING");
        }
    }

    public List<String> citedChunkIdList() {
        return citedChunkIds.isBlank() ? List.of() : Arrays.asList(citedChunkIds.split(","));
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getBreakItemId() { return breakItemId; }
    public UUID getJobId() { return jobId; }
    public RootCauseCategory getRootCause() { return rootCause; }
    public String getExplanation() { return explanation; }
    public String getRecommendedAction() { return recommendedAction; }
    public BigDecimal getConfidence() { return confidence; }
    public boolean isCitationsValid() { return citationsValid; }
    public String getInvalidCitations() { return invalidCitations; }
    public RoutingDecision getRoute() { return route; }
    public String getRouteReason() { return routeReason; }
    public ProposalStatus getStatus() { return status; }
    public String getMakerSubject() { return makerSubject; }
    public BigDecimal getVarianceAmount() { return varianceAmount; }
    public String getCurrencyCode() { return currencyCode; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
