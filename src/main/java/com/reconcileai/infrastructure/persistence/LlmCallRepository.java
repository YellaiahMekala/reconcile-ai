// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.LlmCallRecord;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface LlmCallRepository extends Repository<LlmCallRecord, UUID> {

    LlmCallRecord save(LlmCallRecord call);

    List<LlmCallRecord> findByTenantIdAndJobId(String tenantId, UUID jobId);
}
