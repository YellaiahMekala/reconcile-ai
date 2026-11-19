// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.Evidence;
import com.reconcileai.domain.EvidenceStatus;
import com.reconcileai.infrastructure.ai.ingestion.EvidenceIngestor;
import com.reconcileai.infrastructure.ai.ingestion.EvidenceIngestor.IngestionOutcome;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.EvidenceContentStore;
import com.reconcileai.infrastructure.persistence.EvidenceRepository;
import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Evidence upload with hash dedup (FR-03) + synchronous ingestion.
 *
 * <h2>Flow</h2>
 * <pre>
 *  1. tenant = JWT;  hash = SHA-256(bytes)
 *  2. TX-1  load break (tenant-filtered, 404 otherwise) -> must be accepting evidence (409 otherwise)
 *           existing READY/PROCESSING row with same hash -> return it, deduplicated=true  (NO re-embedding)
 *           existing FAILED row           -> reuse it, mark PROCESSING (retry)
 *           else                          -> insert PROCESSING row + store the original bytes; break -> EVIDENCE_PROCESSING
 *  3. (no TX) Tika -> chunks -> embeddings -> vector_store       [slow, calls OpenAI]
 *  4. TX-2  evidence -> READY/FAILED; break status recomputed from evidence counts
 * </pre>
 *
 * <h2>Why the embedding call is OUTSIDE a transaction</h2>
 * Holding a DB connection open during a multi-second network call to OpenAI starves the Hikari pool under load.
 * The two short transactions around it keep connection time to milliseconds.
 *
 * <h2>Why synchronous (for now)</h2>
 * Deterministic tests and a simple mental model. The trade-off is upload latency on large PDFs; moving this to an
 * async job would change only the call site.
 *
 * <h2>Token optimisation</h2>
 * The hash dedup in step 2 means an identical file re-uploaded (a very common analyst action) costs zero
 * embedding tokens.
 */
@Service
public class EvidenceService {

    private final EvidenceRepository evidenceRepository;
    private final BreakItemRepository breakItemRepository;
    private final TenantContext tenantContext;
    private final EvidenceIngestor ingestor;
    private final TransactionTemplate tx;
    private final AuditRecorder audit;
    private final EvidenceContentStore contentStore;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           BreakItemRepository breakItemRepository,
                           TenantContext tenantContext,
                           EvidenceIngestor ingestor,
                           TransactionTemplate tx,
                           AuditRecorder audit,
                           EvidenceContentStore contentStore) {
        this.audit = audit;
        this.contentStore = contentStore;
        this.evidenceRepository = evidenceRepository;
        this.breakItemRepository = breakItemRepository;
        this.tenantContext = tenantContext;
        this.ingestor = ingestor;
        this.tx = tx;
    }

    public EvidenceUploadResult upload(UUID breakId, String fileName, String contentType, byte[] content) {
        String tenantId = tenantContext.requireTenantId();
        String actor = tenantContext.requireSubject(); // captured on the request thread
        String hash = ContentHasher.sha256(content);

        // ---- TX-1: register (or dedup) ---------------------------------------------------------
        Registration reg = tx.execute(status -> {
            BreakItem breakItem = breakItemRepository.findByIdAndTenantId(breakId, tenantId)
                .orElseThrow(() -> new BreakNotFoundException(breakId));

            Optional<Evidence> existing = evidenceRepository.findByTenantIdAndBreakItemIdAndContentHash(tenantId, breakId, hash);
            if (existing.isPresent() && existing.get().getStatus() != EvidenceStatus.FAILED) {
                return new Registration(existing.get(), true);
            }

            breakItem.requireAcceptingEvidence();
            Evidence evidence = existing.map(e -> { e.markProcessing(); return e; })
                .orElseGet(() -> new Evidence(breakId, tenantId, fileName, contentType, hash, content.length));
            Evidence saved = evidenceRepository.saveAndFlush(evidence); // row must exist before the content row (FK)
            contentStore.save(tenantId, saved.getId(), content); // original file kept for audit and re-ingestion
            breakItem.markEvidenceProcessing();
            breakItemRepository.save(breakItem);
            return new Registration(saved, false);
        });

        if (reg.deduplicated()) {
            return new EvidenceUploadResult(reg.evidence(), true);
        }

        Evidence registered = reg.evidence();
        IngestionOutcome outcome = ingestor.ingest(tenantId, breakId, registered.getId(), fileName, contentType, content);

        // ---- TX-2: record outcome, recompute break readiness ----------------------------------------
        Evidence settled = tx.execute(status -> {
            Evidence e = evidenceRepository.findByTenantIdAndBreakItemIdAndContentHash(tenantId, breakId, hash)
                .orElseThrow(() -> new IllegalStateException("evidence vanished during ingestion"));
            if (outcome.success()) {
                e.markReady(outcome.chunkCount(), outcome.suspectChunkCount());
            } else {
                e.markFailed(outcome.failureReason());
            }
            evidenceRepository.save(e);

            BreakItem breakItem = breakItemRepository.findByIdAndTenantId(breakId, tenantId)
                .orElseThrow(() -> new BreakNotFoundException(breakId));
            breakItem.onEvidenceSettled(
                evidenceRepository.countByTenantIdAndBreakItemIdAndStatus(tenantId, breakId, EvidenceStatus.READY),
                evidenceRepository.countByTenantIdAndBreakItemIdAndStatus(tenantId, breakId, EvidenceStatus.PROCESSING));
            breakItemRepository.save(breakItem);
            audit.record(new AuditEntry(tenantId, outcome.success() ? "EVIDENCE_INGESTED" : "EVIDENCE_FAILED", "EVIDENCE",
                e.getId(), actor, Map.of("breakId", breakId.toString(), "contentHash", hash,
                    "chunks", outcome.chunkCount(), "suspectChunks", outcome.suspectChunkCount(),
                    "reason", outcome.failureReason() == null ? "" : outcome.failureReason())));
            return e;
        });

        return new EvidenceUploadResult(settled, false);
    }

    @Transactional(readOnly = true)
    public List<Evidence> listForBreak(UUID breakId) {
        String tenantId = tenantContext.requireTenantId();
        breakItemRepository.findByIdAndTenantId(breakId, tenantId)
            .orElseThrow(() -> new BreakNotFoundException(breakId));
        return evidenceRepository.findByBreakItemIdAndTenantIdOrderByCreatedAtAsc(breakId, tenantId);
    }

    /** Original file for the audit trail. 404 for another tenant's id and for rows uploaded before V9. */
    @Transactional(readOnly = true)
    public StoredEvidence content(UUID breakId, UUID evidenceId) {
        String tenantId = tenantContext.requireTenantId();
        breakItemRepository.findByIdAndTenantId(breakId, tenantId)
            .orElseThrow(() -> new BreakNotFoundException(breakId));
        Evidence e = evidenceRepository.findByBreakItemIdAndTenantIdOrderByCreatedAtAsc(breakId, tenantId).stream()
            .filter(x -> x.getId().equals(evidenceId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("EVIDENCE_NOT_FOUND", "Evidence not found"));
        byte[] bytes = contentStore.find(tenantId, evidenceId)
            .orElseThrow(() -> new NotFoundException("CONTENT_NOT_AVAILABLE",
                "The original file was not kept for this evidence item"));
        return new StoredEvidence(e.getFileName(), e.getContentType(), bytes);
    }

    public record StoredEvidence(String fileName, String contentType, byte[] bytes) {}

    private record Registration(Evidence evidence, boolean deduplicated) {}
}
