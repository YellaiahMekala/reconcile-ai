package com.reconcileai.application.investigation;

import com.reconcileai.application.BreakNotFoundException;
import com.reconcileai.application.CapacityExceededException;
import com.reconcileai.application.ConflictException;
import com.reconcileai.application.InputRejectedException;
import com.reconcileai.application.RateLimitExceededException;
import com.reconcileai.infrastructure.security.RateLimitProperties;
import com.reconcileai.infrastructure.security.TokenBucketRateLimiter;
import com.reconcileai.application.NotFoundException;
import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.domain.BreakItem;
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
    private final TokenBucketRateLimiter rateLimiter;
    private final RateLimitProperties rateLimit;
    private final InvestigationAgent agent;
    private SupervisedInvestigation supervised;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSupervisedInvestigation(SupervisedInvestigation supervised) {
        this.supervised = supervised;
    }
    private final AgentToolCallStore toolCallStore;

    public InvestigationService(TenantContext tenantContext, BreakItemRepository breaks, EvidenceRepository evidence,
                                InvestigationJobRepository jobs, ResolutionProposalRepository proposals,
                                InvestigationJobRunner runner, InjectionScanner scanner, AuditRecorder audit,
                                InvestigationProperties props, TransactionTemplate tx,
                                TokenBucketRateLimiter rateLimiter, RateLimitProperties rateLimit,
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
        if (mode == InvestigationMode.AGENT && !agent.available()) {
            throw new InputRejectedException("Agent mode is not enabled on this server");
        }
        if (mode.supervised() && (supervised == null || !supervised.available())) {
            throw new InputRejectedException("Supervised mode is not enabled on this server");
        }
        if (rateLimit.enabled()) {
            // Before any DB or model work: a throttled request costs nothing.
            TokenBucketRateLimiter.Decision d = rateLimiter.tryAcquire(user.tenantId());
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
                long ready = evidence.countByTenantIdAndBreakItemIdAndStatus(user.tenantId(), breakId, EvidenceStatus.READY);
                long processing = evidence.countByTenantIdAndBreakItemIdAndStatus(user.tenantId(), breakId, EvidenceStatus.PROCESSING);
                if (ready == 0 || processing > 0) {
                    throw new ConflictException("EVIDENCE_NOT_READY",
                        "Investigation needs at least one READY evidence item and none still PROCESSING");
                }
                b.markInvestigating(); // throws IllegalBreakStateException (409) if lifecycle forbids it
                breaks.save(b);
                InvestigationJob saved = jobs.save(new InvestigationJob(user.tenantId(), breakId, user.subject(), note, mode));
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
            throw new InputRejectedException("mode must be SINGLE_CALL, AGENT, SUPERVISED or SUPERVISED_PLANNED");
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
