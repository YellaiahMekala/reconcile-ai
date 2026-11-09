package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.Evidence;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped access to {@link Evidence}. Same rule as {@link BreakItemRepository}: extends the
 * marker {@link Repository} so no tenant-blind inherited method (findById, findAll) exists.
 */
public interface EvidenceRepository extends Repository<Evidence, UUID> {

    Evidence save(Evidence evidence);

    /** Writes the row now. Needed before a plain-JDBC insert that has a foreign key to it (evidence_content). */
    Evidence saveAndFlush(Evidence evidence);

    /** All evidence for one break, restricted to the caller's tenant. */
    List<Evidence> findByBreakItemIdAndTenantIdOrderByCreatedAtAsc(UUID breakItemId, String tenantId);

    /**
     * Dedup lookup (FR-03): same tenant + same break + same SHA-256 => already uploaded.
     * Backed by the unique constraint uq_evidence_dedup, which also catches the concurrent race.
     */
    Optional<Evidence> findByTenantIdAndBreakItemIdAndContentHash(
        String tenantId, UUID breakItemId, String contentHash);

    /** Readiness guard input: how many of this break's evidence rows are in a given status. */
    long countByTenantIdAndBreakItemIdAndStatus(String tenantId, UUID breakItemId,
                                                com.reconcileai.domain.EvidenceStatus status);
}
