package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.LlmCallRecord;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface LlmCallRepository extends Repository<LlmCallRecord, UUID> {

    LlmCallRecord save(LlmCallRecord call);

    List<LlmCallRecord> findByTenantIdAndJobId(String tenantId, UUID jobId);
}
