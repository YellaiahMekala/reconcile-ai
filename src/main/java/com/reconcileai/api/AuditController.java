// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import com.reconcileai.application.audit.AuditQueryService;
import com.reconcileai.application.audit.AuditQueryService.AuditEventView;
import com.reconcileai.application.audit.AuditQueryService.ChainVerification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 *  GET /api/v1/audit/aggregates/{id}   any app role   events for one break/evidence, in chain order
 *  GET /api/v1/audit/verify            SRE_ADMIN      recompute the caller tenant's whole chain
 * </pre>
 * An unknown or foreign aggregate id returns an empty list: the tenant filter is in the query, so another tenant's
 * events are indistinguishable from "no events".
 */
@RestController
public class AuditController {

    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/audit/aggregates/{aggregateId}")
    @PreAuthorize("hasAnyRole('RECON_ANALYST','APPROVER_L1','APPROVER_L2','SRE_ADMIN')")
    public List<AuditEventView> forAggregate(@PathVariable UUID aggregateId) {
        return service.forAggregate(aggregateId);
    }

    @GetMapping("/api/v1/audit/verify")
    @PreAuthorize("hasRole('SRE_ADMIN')")
    public ChainVerification verify() {
        return service.verifyCurrentTenant();
    }
}
