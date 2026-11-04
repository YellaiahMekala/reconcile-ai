package com.reconcileai.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * A file attached to a break as investigation evidence. Content is hashed on upload;
 * a duplicate (same tenant + same break + same content hash) is skipped, not re-ingested
 * (FR-03). Stores the file bytes and metadata and sets status = PROCESSING.
 * Text extraction and chunking move status to READY or FAILED.
 */
@Entity
@Table(
    name = "evidence",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_evidence_dedup",
        columnNames = {"tenant_id", "break_item_id", "content_hash"}))
public class Evidence {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "break_item_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID breakItemId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @JdbcTypeCode(SqlTypes.CHAR) // V1 column is CHAR(64); see BreakItem.currencyCode for why
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvidenceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Number of chunks written to vector_store (null until ingestion finishes). */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** Chunks quarantined by InjectionScanner: stored for humans, never sent to the LLM. */
    @Column(name = "suspect_chunk_count")
    private Integer suspectChunkCount;

    /** Machine-readable reason when status = FAILED (e.g. NO_EXTRACTABLE_TEXT). Never a stack trace. */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    protected Evidence() { // JPA
    }

    public Evidence(UUID breakItemId, String tenantId, String fileName,
                    String contentType, String contentHash, long sizeBytes) {
        this.breakItemId = breakItemId;
        this.tenantId = tenantId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.status = EvidenceStatus.PROCESSING;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }


    /** Ingestion succeeded: text extracted, chunked, embedded and stored in vector_store. */
    public void markReady(int chunks, int suspectChunks) {
        this.status = EvidenceStatus.READY;
        this.chunkCount = chunks;
        this.suspectChunkCount = suspectChunks;
        this.failureReason = null;
    }

    /** Ingestion failed. The row stays so a re-upload of the same bytes can retry instead of dedup-skipping. */
    public void markFailed(String reason) {
        this.status = EvidenceStatus.FAILED;
        this.chunkCount = 0;
        this.failureReason = reason == null ? "UNKNOWN" : reason.substring(0, Math.min(reason.length(), 512));
    }

    /** Retry path for previously FAILED evidence. */
    public void markProcessing() {
        this.status = EvidenceStatus.PROCESSING;
        this.failureReason = null;
    }

    public UUID getId() { return id; }
    public UUID getBreakItemId() { return breakItemId; }
    public String getTenantId() { return tenantId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public String getContentHash() { return contentHash; }
    public long getSizeBytes() { return sizeBytes; }
    public EvidenceStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Integer getChunkCount() { return chunkCount; }
    public Integer getSuspectChunkCount() { return suspectChunkCount; }
    public String getFailureReason() { return failureReason; }
}
