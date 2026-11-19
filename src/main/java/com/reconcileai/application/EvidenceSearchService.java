// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application;

import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Retrieval preview for analysts ("what would the copilot see for this query?").
 * Useful for debugging retrieval quality and tuning the score floor. Consumes embedding tokens
 * for the query only (one short text), never for evidence.
 */
@Service
public class EvidenceSearchService {

    private final HybridRetriever retriever;
    private final BreakItemRepository breakItemRepository;
    private final TenantContext tenantContext;

    public EvidenceSearchService(HybridRetriever retriever, BreakItemRepository breakItemRepository,
                                 TenantContext tenantContext) {
        this.retriever = retriever;
        this.breakItemRepository = breakItemRepository;
        this.tenantContext = tenantContext;
    }

    public List<RetrievedChunk> search(UUID breakId, String query) {
        String tenantId = tenantContext.requireTenantId();
        // Ownership check first: another tenant's breakId is a 404 before any vector query runs.
        breakItemRepository.findByIdAndTenantId(breakId, tenantId)
            .orElseThrow(() -> new BreakNotFoundException(breakId));
        return retriever.retrieve(tenantId, breakId, query);
    }
}
