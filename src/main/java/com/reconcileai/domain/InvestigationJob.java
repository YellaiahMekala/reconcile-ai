package com.reconcileai.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * An asynchronous AI investigation of one break.
 *
 * <h2>Why a persisted job (not just @Async)</h2>
 * The LLM call can take seconds to minutes. Persisting the job gives: a pollable status for clients, restart
 * recovery (orphaned RUNNING jobs are detected by heartbeat), a DB-enforced "one active job per break" rule, and
 * the retrieval SNAPSHOT that citation verification checks against.
 *
 * <h2>Identity</h2>
 * {@code initiatedBy} is the JWT subject captured on the request thread. Pool threads have no SecurityContext, so the
 * job carries tenant + maker explicitly; it never re-reads identity from the thread.
 */
@Entity
@Table(name = "investigation_job")
public class InvestigationJob {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

    @Column(name = "break_item_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID breakItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStage stage;

    @Column(name = "initiated_by", nullable = false, updatable = false, length = 128)
    private String initiatedBy;

    @Column(name = "analyst_note", length = 1000)
    private String analystNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 24)
    private InvestigationMode mode = InvestigationMode.SINGLE_CALL;

    /** Supervised modes only: AGREE, CHALLENGE or NOT_RUN from the reviewer agent (verdict derived in code). */
    @Column(name = "review_verdict", length = 16)
    private String reviewVerdict;

    /** Supervised modes only: the risk checker's advisory flags, one per line. Shown to the approver; never routes. */
    @Column(name = "risk_flags", columnDefinition = "text")
    private String riskFlags;

    /** Comma-separated vector_store ids that were placed in the prompt. The ONLY ids a proposal may cite. */
    @Column(name = "retrieved_chunk_ids", columnDefinition = "text")
    private String retrievedChunkIds;

    @Column(name = "context_token_estimate")
    private Integer contextTokenEstimate;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    /** The gateway call may have run (and been billed) before the job failed; see V12 and platform Q2.3. */
    @Column(name = "outcome_unknown", nullable = false)
    private boolean outcomeUnknown;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "reconciled_by", length = 128)
    private String reconciledBy;

    /** Gateway invocation id or ticket id recorded by whoever checked the gateway ledger. */
    @Column(name = "reconcile_ref", length = 64)
    private String reconcileRef;

    /** What the gateway ledger said about the referenced invocation when the job was reconciled (V14). */
    @Column(name = "ledger_execution_certainty", length = 32)
    private String ledgerExecutionCertainty;

    @Column(name = "ledger_status", length = 48)
    private String ledgerStatus;

    /** Platform Q1.4: EKA knowledge outcome, reason and refs ("procedure consulted"); null when not used. */
    @Column(name = "knowledge_outcome", length = 16)
    private String knowledgeOutcome;

    @Column(name = "knowledge_reason", length = 48)
    private String knowledgeReason;

    @Column(name = "knowledge_refs", columnDefinition = "text")
    private String knowledgeRefs;

    /** RERO mode only: the internal tool budget, fixed at creation. Null for every other mode (no tool access). */
    @Column(name = "tool_calls_max", updatable = false)
    private Integer toolCallsMax;

    /**
     * RERO mode only: tool calls counted so far. Read-only here: the counter is changed solely by the conditional
     * UPDATE in the internal tool service, so saving this entity can never overwrite it.
     */
    @Column(name = "tool_calls_used", insertable = false, updatable = false)
    private Integer toolCallsUsed;

    @Column(name = "rero_run_id", columnDefinition = "uuid")
    private UUID reroRunId;

    @Column(name = "rero_gateway_invocation_ids", columnDefinition = "text")
    private String reroGatewayInvocationIds;

    @Column(name = "proposal_id", columnDefinition = "uuid")
    private UUID proposalId;

    @Column(name = "owner_node", length = 64)
    private String ownerNode;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected InvestigationJob() { // JPA
    }

    public InvestigationJob(String tenantId, UUID breakItemId, String initiatedBy, String analystNote) {
        this(tenantId, breakItemId, initiatedBy, analystNote, InvestigationMode.SINGLE_CALL);
    }

    public InvestigationJob(String tenantId, UUID breakItemId, String initiatedBy, String analystNote, InvestigationMode mode) {
        this.mode = mode == null ? InvestigationMode.SINGLE_CALL : mode;
        this.tenantId = tenantId;
        this.breakItemId = breakItemId;
        this.initiatedBy = initiatedBy;
        this.analystNote = analystNote;
        this.status = JobStatus.QUEUED;
        this.stage = JobStage.QUEUED;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** RERO mode: the tool budget for this job's run token. Set once, before the job is first saved. */
    public void limitToolCalls(int max) {
        if (mode != InvestigationMode.RERO || id != null) {
            throw new IllegalStateException("tool budget is set on a new RERO job only");
        }
        this.toolCallsMax = max;
    }

    /** RERO correlation: the run id and the gateway invocation ids RERO reported (ids only, never content). */
    public void recordReroRun(UUID runId, List<String> gatewayInvocationIds) {
        this.reroRunId = runId;
        this.reroGatewayInvocationIds = gatewayInvocationIds == null || gatewayInvocationIds.isEmpty()
            ? null : String.join(",", gatewayInvocationIds);
    }

    public Integer getToolCallsMax() { return toolCallsMax; }
    public Integer getToolCallsUsed() { return toolCallsUsed; }
    public UUID getReroRunId() { return reroRunId; }

    public List<String> reroGatewayInvocationIdList() {
        return reroGatewayInvocationIds == null || reroGatewayInvocationIds.isBlank()
            ? List.of() : Arrays.asList(reroGatewayInvocationIds.split(","));
    }

    public void advance(JobStage next) {
        if (isTerminal()) {
            return; // a late stage update from a stalled node must not reopen a failed or finished job
        }
        this.stage = next;
        this.heartbeatAt = Instant.now();
    }

    public void recordRetrieval(List<String> chunkIds, int tokenEstimate) {
        this.retrievedChunkIds = String.join(",", chunkIds);
        this.contextTokenEstimate = tokenEstimate;
    }

    public void recordKnowledge(String outcome, String reason, List<String> refs) {
        this.knowledgeOutcome = outcome;
        this.knowledgeReason = reason == null ? null : reason.substring(0, Math.min(48, reason.length()));
        this.knowledgeRefs = refs == null || refs.isEmpty() ? null : String.join(",", refs);
    }

    public String getKnowledgeOutcome() { return knowledgeOutcome; }

    public String getKnowledgeReason() { return knowledgeReason; }

    public List<String> knowledgeRefList() {
        return knowledgeRefs == null || knowledgeRefs.isBlank() ? List.of() : Arrays.asList(knowledgeRefs.split(","));
    }

    public void succeed(UUID proposalId) {
        this.status = JobStatus.SUCCEEDED;
        this.stage = JobStage.COMPLETED;
        this.proposalId = proposalId;
        this.finishedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = JobStatus.FAILED;
        this.stage = JobStage.FAILED;
        this.failureReason = reason == null ? "UNKNOWN" : reason.substring(0, Math.min(256, reason.length()));
        this.finishedAt = Instant.now();
    }

    public void markOutcomeUnknown() {
        this.outcomeUnknown = true;
    }

    /** Clears the unknown outcome after someone checked the gateway ledger. Only a failed, unreconciled job qualifies. */
    public void reconcile(String by, String reference, Instant at) {
        reconcile(by, reference, at, null, null);
    }

    /**
     * @param ledgerCertainty the gateway ledger's {@code executionCertainty} for the referenced invocation, when
     *                        reconcile-ai read it itself; null for a ticket reference
     * @param ledgerStatus    the ledger's {@code status} for that invocation, or null
     */
    public void reconcile(String by, String reference, Instant at, String ledgerCertainty, String ledgerStatus) {
        if (status != JobStatus.FAILED || !outcomeUnknown || reconciledAt != null) {
            throw new IllegalStateException("JOB_NOT_AWAITING_RECONCILIATION");
        }
        this.reconciledBy = by;
        this.reconcileRef = reference;
        this.reconciledAt = at;
        this.ledgerExecutionCertainty = ledgerCertainty;
        this.ledgerStatus = ledgerStatus;
    }

    public boolean isOutcomeUnknown() { return outcomeUnknown; }

    public String getReconciledBy() { return reconciledBy; }

    public String getReconcileRef() { return reconcileRef; }

    public String getLedgerExecutionCertainty() { return ledgerExecutionCertainty; }

    public String getLedgerStatus() { return ledgerStatus; }

    public Instant getReconciledAt() { return reconciledAt; }

    public boolean isTerminal() {
        return status == JobStatus.SUCCEEDED || status == JobStatus.FAILED;
    }

    public List<String> retrievedChunkIdList() {
        return retrievedChunkIds == null || retrievedChunkIds.isBlank()
            ? List.of() : Arrays.asList(retrievedChunkIds.split(","));
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getBreakItemId() { return breakItemId; }
    public JobStatus getStatus() { return status; }
    public JobStage getStage() { return stage; }
    public String getInitiatedBy() { return initiatedBy; }
    public String getAnalystNote() { return analystNote; }
    public InvestigationMode getMode() { return mode; }
    public String getReviewVerdict() { return reviewVerdict; }

    public java.util.List<String> getRiskFlags() {
        return riskFlags == null || riskFlags.isEmpty() ? java.util.List.of() : java.util.List.of(riskFlags.split("\n"));
    }

    /** Stores what the supervised review produced; called in the same transaction as the proposal. */
    public void recordReview(String verdict, java.util.List<String> flags) {
        this.reviewVerdict = verdict;
        this.riskFlags = flags == null || flags.isEmpty() ? null : String.join("\n", flags);
    }
    public Integer getContextTokenEstimate() { return contextTokenEstimate; }
    public String getFailureReason() { return failureReason; }
    public UUID getProposalId() { return proposalId; }
    public String getOwnerNode() { return ownerNode; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getVersion() { return version; }
}
