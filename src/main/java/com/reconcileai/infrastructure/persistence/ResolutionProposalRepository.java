package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.ResolutionProposal;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped proposal access (marker Repository: no tenant-blind inherited methods). */
public interface ResolutionProposalRepository extends Repository<ResolutionProposal, UUID> {

    ResolutionProposal save(ResolutionProposal proposal);

    Optional<ResolutionProposal> findByIdAndTenantId(UUID id, String tenantId);

    List<ResolutionProposal> findByTenantIdAndBreakItemIdOrderByCreatedAtDesc(String tenantId, UUID breakItemId);
}
