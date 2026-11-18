package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.JobStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Investigation jobs. User-facing reads are tenant-scoped like every other repository.
 *
 * <p>DOCUMENTED EXCEPTION: {@link #findStale} is tenant-blind because orphan recovery is a system process that
 * must see every tenant's jobs. It is only called by InvestigationRecovery, never from a controller path.
 */
public interface InvestigationJobRepository extends Repository<InvestigationJob, UUID> {

    InvestigationJob save(InvestigationJob job);

    Optional<InvestigationJob> findByIdAndTenantId(UUID id, String tenantId);

    /** Newest job for a break (served by idx_job_tenant_break); the console's page-reload view. */
    Optional<InvestigationJob> findFirstByTenantIdAndBreakItemIdOrderByCreatedAtDesc(String tenantId, UUID breakItemId);

    /** True while a failed job for this break has an unknown gateway outcome nobody has reconciled (platform Q2.3). */
    boolean existsByTenantIdAndBreakItemIdAndOutcomeUnknownTrueAndReconciledAtIsNull(String tenantId, UUID breakItemId);

    boolean existsByTenantIdAndBreakItemIdAndStatusIn(String tenantId, UUID breakItemId, Collection<JobStatus> statuses);

    /**
     * Atomic claim: QUEUED -> RUNNING for exactly one worker. If two nodes (or a recovery resubmit and the original
     * submit) race, only one UPDATE matches, so a job can never run twice and never spend tokens twice.
     *
     * @return 1 if this caller claimed the job, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InvestigationJob j
           SET j.status = com.reconcileai.domain.JobStatus.RUNNING,
               j.ownerNode = :node, j.heartbeatAt = :now, j.startedAt = :now, j.version = j.version + 1
         WHERE j.id = :id AND j.tenantId = :tenantId AND j.status = com.reconcileai.domain.JobStatus.QUEUED""")
    int claim(@Param("id") UUID id, @Param("tenantId") String tenantId, @Param("node") String node, @Param("now") Instant now);

    /** System recovery only: RUNNING jobs whose heartbeat is older than the lease, or QUEUED jobs never picked up. */
    @Query("""
        SELECT j FROM InvestigationJob j
         WHERE (j.status = com.reconcileai.domain.JobStatus.RUNNING AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff))
            OR (j.status = com.reconcileai.domain.JobStatus.QUEUED AND j.createdAt < :cutoff)""")
    List<InvestigationJob> findStale(@Param("cutoff") Instant cutoff);
}
