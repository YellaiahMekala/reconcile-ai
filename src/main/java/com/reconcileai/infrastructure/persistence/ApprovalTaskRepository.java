// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.ApprovalStatus;
import com.reconcileai.domain.ApprovalTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped approval queue (marker Repository: no tenant-blind inherited methods). */
public interface ApprovalTaskRepository extends Repository<ApprovalTask, UUID> {

    ApprovalTask save(ApprovalTask task);

    Optional<ApprovalTask> findByIdAndTenantId(UUID id, String tenantId);

    Optional<ApprovalTask> findByProposalIdAndTenantId(UUID proposalId, String tenantId);

    List<ApprovalTask> findByTenantIdAndStatusAndRequiredRoleInOrderByCreatedAtAsc(
        String tenantId, ApprovalStatus status, Collection<String> requiredRoles, Pageable page);
}
