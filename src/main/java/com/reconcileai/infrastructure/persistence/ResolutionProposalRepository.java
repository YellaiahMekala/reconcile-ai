package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.domain.ProposalStatus;
import com.reconcileai.domain.RootCauseCategory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped proposal access (marker Repository: no tenant-blind inherited methods). */
public interface ResolutionProposalRepository extends Repository<ResolutionProposal, UUID> {

    ResolutionProposal save(ResolutionProposal proposal);

    Optional<ResolutionProposal> findByIdAndTenantId(UUID id, String tenantId);

    List<ResolutionProposal> findByTenantIdAndBreakItemIdOrderByCreatedAtDesc(String tenantId, UUID breakItemId);

    /** Earlier approved proposals of this tenant, any root cause, newest first (RERO similar-breaks tool, Q3.2). */
    @Query("""
        select p from ResolutionProposal p
         where p.tenantId = :tenantId and p.status = :status and p.breakItemId <> :excludeBreakId
         order by p.createdAt desc""")
    List<ResolutionProposal> findRecentResolved(@Param("tenantId") String tenantId,
                                                @Param("status") ProposalStatus status,
                                                @Param("excludeBreakId") UUID excludeBreakId, Pageable page);

    /** Earlier proposals of this tenant that an approver accepted, for the agent's "similar resolved breaks" tool. */
    @Query("""
        select p from ResolutionProposal p
         where p.tenantId = :tenantId and p.status = :status and p.rootCause = :rootCause
           and p.breakItemId <> :excludeBreakId
         order by p.createdAt desc""")
    List<ResolutionProposal> findSimilarResolved(@Param("tenantId") String tenantId,
                                                 @Param("status") ProposalStatus status,
                                                 @Param("rootCause") RootCauseCategory rootCause,
                                                 @Param("excludeBreakId") UUID excludeBreakId, Pageable page);
}
