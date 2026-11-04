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
