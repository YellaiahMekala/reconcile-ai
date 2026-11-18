package com.reconcileai.application.investigation;

import com.reconcileai.application.BreakNotFoundException;
import com.reconcileai.application.CapacityExceededException;
import com.reconcileai.application.ConflictException;
import com.reconcileai.application.InputRejectedException;
import com.reconcileai.application.RateLimitExceededException;
import com.reconcileai.infrastructure.security.RateLimitProperties;
import com.reconcileai.infrastructure.security.RateLimiter;
import com.reconcileai.application.NotFoundException;
import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.application.gateway.GatewayLedgerPort;
import com.reconcileai.application.gateway.GatewayLedgerUnavailableException;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.GatewayCallRecord;
import com.reconcileai.infrastructure.persistence.GatewayCallRepository;
import com.reconcileai.domain.EvidenceStatus;
import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.InvestigationMode;
import com.reconcileai.infrastructure.ai.agent.AgentToolCallStore;
import com.reconcileai.infrastructure.ai.agent.InvestigationAgent;
import com.reconcileai.infrastructure.ai.agent.SupervisedInvestigation;
import com.reconcileai.domain.JobStatus;
import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.EvidenceRepository;
import com.reconcileai.infrastructure.persistence.InvestigationJobRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import com.reconcileai.infrastructure.security.CurrentUser;
import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Request-side use cases: start an investigation, read job status and proposals.
 *
 * <h2>Start flow</h2>
 * <pre>
 *  1. identity = JWT (captured HERE, on the request thread); per-tenant rate limit                -> 429
 *  2. note checks (length, injection tripwire)                  -> 400, zero tokens
 *  3. TX: break (tenant) -> canStartInvestigation              -> 404 / 409 ILLEGAL_BREAK_STATE
 *         evidence READY >= 1 and PROCESSING == 0               -> 409 EVIDENCE_NOT_READY
 *         no active job (plus DB partial unique index)          -> 409 INVESTIGATION_IN_PROGRESS
 *         insert QUEUED job, break -> INVESTIGATING, audit
 *  4. after commit: submit to bounded executor                 -> 503 if full (job + break rolled to failed)
 *  5. return 202 Accepted + job
 * </pre>
 */
@Service
public class InvestigationService {

    private final TenantContext tenantContext;
    private final BreakItemRepository breaks;
    private final EvidenceRepository evidence;
    private final InvestigationJobRepository jobs;
    private final ResolutionProposalRepository proposals;
    private final InvestigationJobRunner runner;
    private final InjectionScanner scanner;
    private final AuditRecorder audit;
    private final InvestigationProperties props;
    private final TransactionTemplate tx;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimit;
    private final InvestigationAgent agent;
    private SupervisedInvestigation supervised;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSupervisedInvestigation(SupervisedInvestigation supervised) {
        this.supervised = supervised;
    }
    private final AgentToolCallStore toolCallStore;
    private GatewayLedgerPort ledger;
    private GatewayCallRepository gatewayCalls;
    private com.reconcileai.application.gateway.GatewayChatPort gatewayChat;

    /** Present only when the gateway route is on; then only governed (SINGLE_CALL) jobs may start. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGatewayChat(com.reconcileai.application.gateway.GatewayChatPort gatewayChat) {
        this.gatewayChat = gatewayChat;
    }

    /** Present only when the gateway route is on (GatewayConfig). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGatewayLedger(GatewayLedgerPort ledger) {
        this.ledger = ledger;
    }

    private com.reconcileai.infrastructure.rero.ReroProperties rero;

    /** Platform Q3: the RERO mode switch and its governance declaration. Absent only in slices without properties. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReroProperties(com.reconcileai.infrastructure.rero.ReroProperties rero) {
        this.rero = rero;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setGatewayCallRepository(GatewayCallRepository gatewayCalls) {
        this.gatewayCalls = gatewayCalls;
    }

    public InvestigationService(TenantContext tenantContext, BreakItemRepository breaks, EvidenceRepository evidence,
                                InvestigationJobRepository jobs, ResolutionProposalRepository proposals,
                                InvestigationJobRunner runner, InjectionScanner scanner, AuditRecorder audit,
                                InvestigationProperties props, TransactionTemplate tx,
                                RateLimiter rateLimiter, RateLimitProperties rateLimit,
                                InvestigationAgent agent, AgentToolCallStore toolCallStore) {
        this.agent = agent;
        this.toolCallStore = toolCallStore;
        this.rateLimiter = rateLimiter;
        this.rateLimit = rateLimit;
        this.tenantContext = tenantContext;
        this.breaks = breaks;
        this.evidence = evidence;
        this.jobs = jobs;
        this.proposals = proposals;
        this.runner = runner;
        this.scanner = scanner;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
    }

    public InvestigationJob start(UUID breakId, String analystNote) {
        return start(breakId, analystNote, null);
    }

    /**
     * @param modeName SINGLE_CALL (or null) for the original flow, AGENT for the tool-using agent, SUPERVISED or
     *                 SUPERVISED_PLANNED for the specialist agents
     */
    public InvestigationJob start(UUID breakId, String analystNote, String modeName) {
        CurrentUser user = tenantContext.currentUser();
        InvestigationMode mode = parseMode(modeName);
        if (mode == InvestigationMode.RERO && (rero == null || !rero.enabled())) {
            throw new ConflictException("RERO_DISABLED", "RERO mode is not enabled on this server");
        }
        // RERO counts as governed only when the operator declares that RERO's own model calls go through ai-gateway
        // (reconcile.rero.model-via-gateway, i.e. RERO runs GatewayModel). reconcile-ai cannot see RERO's model calls,
        // so without that declaration it is treated like the agent modes and refused while the gateway route is on.
        boolean governedRero = mode == InvestigationMode.RERO && rero != null && rero.modelViaGateway();
        if (mode != InvestigationMode.SINGLE_CALL && !governedRero && gatewayChat != null) {
            // Agent and supervised modes call the model directly, not through ai-gateway. With the gateway route on,
            // allowing them would skip its budget, risk checks and ledger, so they are refused until they are routed.
            throw new ConflictException("MODE_NOT_GOVERNED",
                "Only SINGLE_CALL runs through ai-gateway; " + mode.name() + " is disabled while the gateway route is on");
        }
        if (mode == InvestigationMode.AGENT && !agent.available()) {
            throw new InputRejectedException("Agent mode is not enabled on this server");
        }
        if (mode.supervised() && (supervised == null || !supervised.available())) {
            throw new InputRejectedException("Supervised mode is not enabled on this server");
        }
        if (rateLimit.enabled()) {
            // Before any DB or model work: a throttled request costs nothing.
            RateLimiter.Decision d = rateLimiter.tryAcquire(user.tenantId());
            if (!d.allowed()) {
                throw new RateLimitExceededException(d.retryAfterSeconds());
            }
        }
        String note = analystNote == null || analystNote.isBlank() ? null : analystNote.strip();
        if (note != null && note.length() > props.maxAnalystNoteChars()) {
            throw new InputRejectedException("Analyst note exceeds " + props.maxAnalystNoteChars() + " characters");
        }
        if (note != null && scanner.isSuspect(note)) {
            throw new InputRejectedException("Analyst note rejected by input guard");
        }

        InvestigationJob job;
        try {
            job = tx.execute(status -> {
                BreakItem b = breaks.findByIdAndTenantId(breakId, user.tenantId())
                    .orElseThrow(() -> new BreakNotFoundException(breakId));
                if (jobs.existsByTenantIdAndBreakItemIdAndStatusIn(user.tenantId(), breakId,
                        EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
                    throw new ConflictException("INVESTIGATION_IN_PROGRESS", "An investigation is already running for this break");
                }
                if (jobs.existsByTenantIdAndBreakItemIdAndOutcomeUnknownTrueAndReconciledAtIsNull(user.tenantId(), breakId)) {
                    // A new job gets a new idempotency key, so the gateway cannot replay the earlier call: re-running
                    // could bill the same work twice. A person must check the gateway ledger first (platform Q2.3).
                    throw new ConflictException("OUTCOME_UNRECONCILED",
                        "An earlier gateway call for this break may have run; reconcile it before re-investigating");
                }
                long ready = evidence.countByTenantIdAndBreakItemIdAndStatus(user.tenantId(), breakId, EvidenceStatus.READY);
                long processing = evidence.countByTenantIdAndBreakItemIdAndStatus(user.tenantId(), breakId, EvidenceStatus.PROCESSING);
                if (ready == 0 || processing > 0) {
                    throw new ConflictException("EVIDENCE_NOT_READY",
                        "Investigation needs at least one READY evidence item and none still PROCESSING");
                }
                b.markInvestigating(); // throws IllegalBreakStateException (409) if lifecycle forbids it
                breaks.save(b);
                InvestigationJob fresh = new InvestigationJob(user.tenantId(), breakId, user.subject(), note, mode);
                if (mode == InvestigationMode.RERO) {
                    fresh.limitToolCalls(rero.maxToolCalls()); // the run token's budget, counted in the DB
                }
                InvestigationJob saved = jobs.save(fresh);
                audit.record(new AuditEntry(user.tenantId(), "INVESTIGATION_REQUESTED", "BREAK", breakId, user.subject(),
                    Map.of("jobId", saved.getId().toString(), "mode", mode.name())));
                return saved;
            });
        } catch (DataIntegrityViolationException raceLost) {
            // Two concurrent POSTs both passed the exists-check; the partial unique index rejected the second.
            throw new ConflictException("INVESTIGATION_IN_PROGRESS", "An investigation is already running for this break");
        }

        try {
            runner.submit(job.getId(), job.getTenantId());
        } catch (RejectedExecutionException full) {
            runner.failJob(job.getId(), job.getTenantId(), "CAPACITY_EXCEEDED");
            throw new CapacityExceededException("Investigation capacity exceeded, retry later");
        }
        return job;
    }

    private static final java.util.regex.Pattern RECONCILE_REF = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{3,64}");

    /**
     * Records that someone checked the gateway ledger for a failed job whose outcome was unknown, which lifts the
     * re-run block for its break. {@code reference} is the gateway invocation id or a ticket id, never free text.
     * The person who started the job cannot clear it.
     * <p>
     * When the reference is an invocation id and the gateway route is on, reconcile-ai reads the gateway ledger
     * itself before recording anything: the id must be one this job's own calls received ({@code gateway_call}),
     * the ledger must know it, and its {@code executionCertainty} and {@code status} are stored on the job and in
     * the audit entry. The decision stays with the person: an EXECUTED call can still be reconciled (the re-run will
     * bill again, knowingly). A ticket reference skips the read, as before.
     */
    public InvestigationJob reconcile(UUID jobId, String reference) {
        CurrentUser user = tenantContext.currentUser();
        if (reference == null || !RECONCILE_REF.matcher(reference).matches()) {
            throw new InputRejectedException("reference must be 3 to 64 characters of letters, digits and . _ : -");
        }
        GatewayLedgerPort.Entry ledgerEntry = readLedger(user, jobId, reference);
        return tx.execute(status -> {
            InvestigationJob job = jobs.findByIdAndTenantId(jobId, user.tenantId())
                .orElseThrow(() -> new NotFoundException("JOB_NOT_FOUND", "Investigation job not found"));
            requireReconcilable(job, user);
            String certainty = ledgerEntry == null ? null : ledgerEntry.executionCertainty();
            String ledgerStatus = ledgerEntry == null ? null : ledgerEntry.status();
            job.reconcile(user.subject(), reference, java.time.Instant.now(), certainty, ledgerStatus);
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("jobId", jobId.toString());
            details.put("reference", reference);
            details.put("ledgerChecked", ledgerEntry != null);
            if (ledgerEntry != null) {
                details.put("ledgerExecutionCertainty", String.valueOf(certainty));
                details.put("ledgerStatus", String.valueOf(ledgerStatus));
                details.put("ledgerInputTokens", ledgerEntry.inputTokens());
                details.put("ledgerOutputTokens", ledgerEntry.outputTokens());
                if (ledgerEntry.actualCostUsd() != null) {
                    details.put("ledgerCostUsd", ledgerEntry.actualCostUsd().toPlainString());
                }
            }
            audit.record(new AuditEntry(user.tenantId(), "INVESTIGATION_OUTCOME_RECONCILED", "BREAK", job.getBreakItemId(),
                user.subject(), details));
            return jobs.save(job);
        });
    }

    private static void requireReconcilable(InvestigationJob job, CurrentUser user) {
        if (user.subject().equals(job.getInitiatedBy())) {
            throw new ConflictException("RECONCILER_MUST_DIFFER", "The person who started the job cannot reconcile it");
        }
        if (job.getStatus() != JobStatus.FAILED || !job.isOutcomeUnknown() || job.getReconciledAt() != null) {
            throw new ConflictException("NOT_AWAITING_RECONCILIATION", "This job has no unreconciled gateway outcome");
        }
    }

    /**
     * The gateway ledger row behind an invocation-id reference, or null when no read applies (a ticket reference, or
     * the gateway route is off). The checks run before the HTTP call, outside the write transaction, which repeats
     * them.
     */
    private GatewayLedgerPort.Entry readLedger(CurrentUser user, UUID jobId, String reference) {
        UUID invocationId = parseUuid(reference);
        if (invocationId == null || ledger == null) {
            return null;
        }
        List<UUID> ours = tx.execute(status -> {
            InvestigationJob job = jobs.findByIdAndTenantId(jobId, user.tenantId())
                .orElseThrow(() -> new NotFoundException("JOB_NOT_FOUND", "Investigation job not found"));
            requireReconcilable(job, user);
            return gatewayCalls.findByTenantIdAndJobIdOrderByAttempt(user.tenantId(), jobId).stream()
                .map(GatewayCallRecord::getInvocationId)
                .filter(java.util.Objects::nonNull)
                .toList();
        });
        if (ours == null || !ours.contains(invocationId)) {
            throw new ConflictException("INVOCATION_NOT_FOR_JOB",
                "That invocation id was not returned to any gateway call of this job");
        }
        GatewayLedgerPort.Lookup lookup = ledger.lookup(user.tenantId(), invocationId);
        return switch (lookup.result()) {
            case FOUND -> lookup.entry();
            case NOT_FOUND -> throw new ConflictException("LEDGER_ENTRY_NOT_FOUND",
                "The gateway ledger has no entry for that invocation id");
            case UNAVAILABLE -> throw new GatewayLedgerUnavailableException(
                "The gateway ledger could not be read; retry later or reconcile with a ticket reference");
        };
    }

    private static UUID parseUuid(String reference) {
        try {
            UUID id = UUID.fromString(reference);
            // UUID.fromString accepts short forms such as "1-2-3-4-5"; only the canonical 36-character form counts.
            return id.toString().equalsIgnoreCase(reference) ? id : null;
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * This job's gateway calls (our {@code gateway_call} rows), each with the gateway ledger's view of its
     * invocation when the route is on. What an approver looks at before reconciling. Read-only; 404 for another
     * tenant's job. The rows are read in a short transaction; the ledger HTTP reads happen after it, so no database
     * connection is held across a network call.
     */
    public List<GatewayCallView> gatewayCalls(UUID jobId) {
        CurrentUser user = tenantContext.currentUser();
        List<GatewayCallRecord> rows = tx.execute(status -> {
            jobs.findByIdAndTenantId(jobId, user.tenantId())
                .orElseThrow(() -> new NotFoundException("JOB_NOT_FOUND", "Investigation job not found"));
            return gatewayCalls.findByTenantIdAndJobIdOrderByAttempt(user.tenantId(), jobId);
        });
        return (rows == null ? List.<GatewayCallRecord>of() : rows).stream()
            .map(c -> new GatewayCallView(c, ledgerFor(user.tenantId(), c.getInvocationId())))
            .toList();
    }

    private GatewayLedgerPort.Lookup ledgerFor(String tenantId, UUID invocationId) {
        if (invocationId == null || ledger == null) {
            return null;
        }
        return ledger.lookup(tenantId, invocationId);
    }

    /** One {@code gateway_call} row plus the ledger lookup; {@code ledger} is null when there was nothing to look up. */
    public record GatewayCallView(GatewayCallRecord call, GatewayLedgerPort.Lookup ledger) { }

    /** The tool calls an AGENT or supervised job made, in order. Empty for single-call jobs. 404 for another tenant's job. */
    @Transactional(readOnly = true)
    public List<AgentToolCallStore.StoredToolCall> toolCalls(UUID jobId) {
        InvestigationJob job = getJob(jobId);
        return toolCallStore.find(job.getTenantId(), job.getId());
    }

    private static InvestigationMode parseMode(String name) {
        if (name == null || name.isBlank()) {
            return InvestigationMode.SINGLE_CALL;
        }
        try {
            return InvestigationMode.valueOf(name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InputRejectedException("mode must be SINGLE_CALL, AGENT, SUPERVISED, SUPERVISED_PLANNED or RERO");
        }
    }

    @Transactional(readOnly = true)
    public InvestigationJob getJob(UUID jobId) {
        return jobs.findByIdAndTenantId(jobId, tenantContext.requireTenantId())
            .orElseThrow(() -> new NotFoundException("JOB_NOT_FOUND", "Investigation job not found"));
    }

    @Transactional(readOnly = true)
    public ResolutionProposal getProposal(UUID proposalId) {
        return proposals.findByIdAndTenantId(proposalId, tenantContext.requireTenantId())
            .orElseThrow(() -> new NotFoundException("PROPOSAL_NOT_FOUND", "Proposal not found"));
    }

    @Transactional(readOnly = true)
    public List<ResolutionProposal> proposalsForBreak(UUID breakId) {
        String tenantId = tenantContext.requireTenantId();
        breaks.findByIdAndTenantId(breakId, tenantId).orElseThrow(() -> new BreakNotFoundException(breakId));
        return proposals.findByTenantIdAndBreakItemIdOrderByCreatedAtDesc(tenantId, breakId);
    }
}
