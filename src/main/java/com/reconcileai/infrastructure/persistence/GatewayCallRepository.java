package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.GatewayCallRecord;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface GatewayCallRepository extends Repository<GatewayCallRecord, UUID> {

    GatewayCallRecord save(GatewayCallRecord call);

    List<GatewayCallRecord> findByTenantIdAndJobIdOrderByAttempt(String tenantId, UUID jobId);
}
