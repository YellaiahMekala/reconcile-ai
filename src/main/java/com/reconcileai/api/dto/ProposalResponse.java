// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api.dto;

import com.reconcileai.domain.ResolutionProposal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What an approver reviews: the AI's claim, its citations, whether they verified, and why the policy routed it. */
public record ProposalResponse(
    UUID id, UUID breakItemId, UUID jobId, String rootCause, String explanation, String recommendedAction,
    BigDecimal confidence, List<String> citedChunkIds, boolean citationsValid, String invalidCitations,
    String route, String routeReason, String status, String makerSubject,
    BigDecimal varianceAmount, String currency, Instant createdAt
) {
    public static ProposalResponse from(ResolutionProposal p) {
        return new ProposalResponse(p.getId(), p.getBreakItemId(), p.getJobId(), p.getRootCause().name(),
            p.getExplanation(), p.getRecommendedAction(), p.getConfidence(), p.citedChunkIdList(), p.isCitationsValid(),
            p.getInvalidCitations(), p.getRoute().name(), p.getRouteReason(), p.getStatus().name(), p.getMakerSubject(),
            p.getVarianceAmount(), p.getCurrencyCode(), p.getCreatedAt());
    }
}
