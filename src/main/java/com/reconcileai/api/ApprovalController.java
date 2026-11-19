// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import com.reconcileai.api.dto.ApprovalTaskResponse;
import com.reconcileai.application.approval.ApprovalService;
import com.reconcileai.domain.ApprovalDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 *  GET  /api/v1/approvals                  APPROVER_L1|L2   my OPEN queue (excludes tasks where I am the maker)
 *  GET  /api/v1/approvals/{taskId}         any app role     200 | 404
 *  POST /api/v1/approvals/{taskId}/decision APPROVER_L1|L2  200 | 400 | 403 | 404 | 409
 * </pre>
 * {@code @PreAuthorize} is the coarse gate (must hold some approver role). Tier and maker-checker are data-dependent
 * and are enforced in ApprovalService, which is where they cannot be bypassed by a new controller.
 */
@RestController
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /** Body has no approver, tenant, or tier fields by design: those come from the JWT and the stored proposal. */
    public record DecisionRequest(@NotNull ApprovalDecision decision, @Size(max = 1000) String comment) {}

    @GetMapping("/api/v1/approvals")
    @PreAuthorize("hasAnyRole('APPROVER_L1','APPROVER_L2')")
    public List<ApprovalTaskResponse> myQueue() {
        return service.myQueue().stream().map(ApprovalTaskResponse::from).toList();
    }

    @GetMapping("/api/v1/approvals/{taskId}")
    @PreAuthorize("hasAnyRole('RECON_ANALYST','APPROVER_L1','APPROVER_L2','SRE_ADMIN')")
    public ApprovalTaskResponse get(@PathVariable UUID taskId) {
        return ApprovalTaskResponse.from(service.get(taskId));
    }

    @PostMapping("/api/v1/approvals/{taskId}/decision")
    @PreAuthorize("hasAnyRole('APPROVER_L1','APPROVER_L2')")
    public ApprovalTaskResponse decide(@PathVariable UUID taskId, @Valid @RequestBody DecisionRequest body) {
        return ApprovalTaskResponse.from(service.decide(taskId, body.decision(), body.comment()));
    }
}
