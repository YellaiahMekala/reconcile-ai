// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Port for the audit trail. Callers invoke it INSIDE the same transaction as the business change, so an audit
 * entry exists if and only if the change committed.
 */
public interface AuditRecorder {

    void record(AuditEntry entry);

    /**
     * @param actor JWT subject of the human, or "system:&lt;component&gt;" for automated steps
     */
    record AuditEntry(String tenantId, String eventType, String aggregateType, UUID aggregateId,
                      String actor, Map<String, Object> details) {}
}
