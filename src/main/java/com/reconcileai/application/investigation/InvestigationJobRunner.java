// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import com.reconcileai.infrastructure.security.TenantScope;
import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.application.investigation.CitationVerifier.CitationResult;
import com.reconcileai.application.investigation.InvestigationExecutorConfig.NodeIdentity;
import com.reconcileai.application.policy.PolicyGateService;
import com.reconcileai.application.policy.PolicyGateService.PolicyDecision;
import com.reconcileai.application.policy.PolicyGateService.PolicyInput;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.JobStage;
import com.reconcileai.domain.LlmCallRecord;
import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.domain.InvestigationMode;
import com.reconcileai.infrastructure.ai.InvestigationAiClient;
import com.reconcileai.infrastructure.ai.agent.InvestigationAgent;
import com.reconcileai.infrastructure.ai.agent.ReviewerVerdict;
import com.reconcileai.infrastructure.ai.agent.SupervisedInvestigation;
import com.reconcileai.application.gateway.GatewayCallException;
import com.reconcileai.application.gateway.GatewayFailure;
import com.reconcileai.application.knowledge.KnowledgeHit;
import com.reconcileai.application.knowledge.KnowledgeOutcome;
import com.reconcileai.application.knowledge.KnowledgeResult;
import com.reconcileai.infrastructure.ai.advisor.InputGuardAdvisor.PromptBlockedException;
import com.reconcileai.infrastructure.ai.cache.SemanticCache;
import com.reconcileai.infrastructure.ai.prompt.ProposalDraft;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.BuiltPrompt;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.NormalizedDraft;
import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.InvestigationJobRepository;
import com.reconcileai.infrastructure.persistence.LlmCallRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes one investigation on a pool thread. Every step that touches the DB is its own short transaction;
 * retrieval and the model call happen OUTSIDE transactions (no connection held during network I/O).
 *
 * <pre>
 *  claim QUEUED->RUNNING (atomic UPDATE; losers exit)                       stage RETRIEVING
 *  hybrid retrieval (tenant+break scoped) -> prompt budget -> snapshot ids   stage CALLING_MODEL
 *    └─ no usable evidence? -> MANUAL_REVIEW proposal WITHOUT calling the model (0 tokens)
 *  ChatClient call (guard -> usage -> model) -> parse -> normalize           stage VERIFYING
 *  citation verification against snapshot                                   stage ROUTING
 *  policy gate -> TX: persistProposal + job SUCCEEDED + audit                stage COMPLETED
 *  any exception -> TX: job FAILED + break FAILED_RETRYABLE + audit          stage FAILED
 * </pre>
 * Identity: tenantId and maker come from the job row. This thread has no SecurityContext and never needs one.
 */
@Component
public class InvestigationJobRunner {

    private static final Logger log = LoggerFactory.getLogger(InvestigationJobRunner.class);
    private static final String ACTOR = "system:investigation-runner";

    private final TaskExecutor executor;
    private final InvestigationJobRepository jobs;
    private final BreakItemRepository breaks;
    private final LlmCallRepository llmCalls;
    private final HybridRetriever retriever;
    private final InvestigationPromptBuilder promptBuilder;
    private final InvestigationAiClient aiClient;
    private final InvestigationAgent agent;
    private final CitationVerifier citationVerifier;
    private final PolicyGateService policyGate;
    private final ProposalService proposalService;
    private final AuditRecorder audit;
    private final InvestigationProgressPublisher progress;
    private final InvestigationProperties props;
    private final NodeIdentity node;
    private final TransactionTemplate tx;
    private ObservationRegistry observations = ObservationRegistry.NOOP;
    private SemanticCache cache = SemanticCache.DISABLED;
    private io.micrometer.core.instrument.MeterRegistry meters;
    private SupervisedInvestigation supervised;
    private com.reconcileai.application.knowledge.KnowledgeSearchPort knowledge;
    private boolean knowledgeFailClosed;
    private boolean breakSpecificQuery;
    private com.reconcileai.application.knowledge.KnowledgeRelevanceFilter knowledgeFilter =
        new com.reconcileai.application.knowledge.KnowledgeRelevanceFilter(null);

    /**
     * Optional: EKA procedure knowledge (platform Q1), present only with {@code reconcile.knowledge.enabled=true}.
     * Used by the single-call mode only, as a deterministic pre-step before the one (possibly gateway-governed) model
     * call; the agent modes never reach EKA.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKnowledge(com.reconcileai.application.knowledge.KnowledgeSearchPort knowledge,
                      com.reconcileai.infrastructure.knowledge.KnowledgeProperties knowledgeProperties) {
        this.knowledge = knowledge;
        this.knowledgeFailClosed = knowledgeProperties.failClosed();
        this.breakSpecificQuery = knowledgeProperties.breakSpecificQuery();
        this.knowledgeFilter = new com.reconcileai.application.knowledge.KnowledgeRelevanceFilter(
            new com.reconcileai.application.knowledge.KnowledgeRelevanceFilter.Settings(
                knowledgeProperties.relevanceFilterEnabled(), knowledgeProperties.minScore(),
                knowledgeProperties.categoryMatch()));
    }

    private com.reconcileai.application.rero.ReroInvestigation rero;

    /** Optional: the RERO mode (platform Q3.5), present only with {@code reconcile.rero.enabled=true}. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReroInvestigation(com.reconcileai.application.rero.ReroInvestigation rero) {
        this.rero = rero;
    }

    /** Optional: the supervised modes (specialist agents). Missing only in slices that do not load the agent package. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSupervisedInvestigation(SupervisedInvestigation supervised) {
        this.supervised = supervised;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSemanticCache(SemanticCache cache) {
        this.cache = cache;
    }

    /** Optional: counts failed jobs ({@code reconcile.investigation.failed}) so Prometheus can alert on them. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
    }

    /** Optional: one span per job (retrieval, model call and DB work nest under it). NOOP when tracing is off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setObservationRegistry(ObservationRegistry registry) {
        this.observations = registry;
    }

    @SuppressWarnings("java:S107")
    public InvestigationJobRunner(@Qualifier("investigationExecutor") TaskExecutor executor,
                                  InvestigationJobRepository jobs, BreakItemRepository breaks, LlmCallRepository llmCalls,
                                  HybridRetriever retriever, InvestigationPromptBuilder promptBuilder,
                                  InvestigationAiClient aiClient, CitationVerifier citationVerifier,
                                  PolicyGateService policyGate, ProposalService proposalService, AuditRecorder audit,
                                  InvestigationProgressPublisher progress, InvestigationProperties props,
                                  NodeIdentity node, TransactionTemplate tx, InvestigationAgent agent) {
        this.agent = agent;
        this.executor = executor;
        this.jobs = jobs;
        this.breaks = breaks;
        this.llmCalls = llmCalls;
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.aiClient = aiClient;
        this.citationVerifier = citationVerifier;
        this.policyGate = policyGate;
        this.proposalService = proposalService;
        this.audit = audit;
        this.progress = progress;
        this.props = props;
        this.node = node;
        this.tx = tx;
    }

    /** @throws java.util.concurrent.RejectedExecutionException when the bounded queue is full */
    public void submit(UUID jobId, String tenantId) {
        executor.execute(() -> Observation.createNotStarted("reconcile.investigation", observations)
            .contextualName("investigation-job")
            .lowCardinalityKeyValue("component", "investigation-runner")
            .highCardinalityKeyValue("job.id", jobId.toString())
            .observe(() -> TenantScope.run(tenantId, () -> run(jobId, tenantId))));
    }

    void run(UUID jobId, String tenantId) {
        Integer claimed = tx.execute(s -> jobs.claim(jobId, tenantId, node.id(), Instant.now()));
        if (claimed == null || claimed == 0) {
            log.debug("job {} already claimed elsewhere; skipping", jobId);
            return;
        }
        try {
            InvestigationJob job = stage(jobId, tenantId, JobStage.RETRIEVING, null);
            BreakItem breakItem = breaks.findByIdAndTenantId(job.getBreakItemId(), tenantId).orElseThrow();
            if (job.getMode() == InvestigationMode.AGENT) {
                runAgent(jobId, tenantId, job, breakItem);
                return;
            }
            if (job.getMode().supervised()) {
                runSupervised(jobId, tenantId, job, breakItem);
                return;
            }
            if (job.getMode() == InvestigationMode.RERO) {
                runRero(jobId, tenantId, job, breakItem);
                return;
            }

            String query = promptBuilder.retrievalQuery(breakItem, job.getAnalystNote());
            List<RetrievedChunk> ranked = retriever.retrieve(tenantId, breakItem.getId(), query);
            List<KnowledgeHit> procedures = ranked.isEmpty() ? List.of()
                : consultKnowledge(jobId, tenantId, job, breakItem, ranked);
            BuiltPrompt prompt = promptBuilder.build(breakItem, job.getAnalystNote(), ranked, props.maxContextTokens(),
                procedures);
            job = stage(jobId, tenantId, JobStage.CALLING_MODEL,
                j -> j.recordRetrieval(prompt.includedChunkIds(), prompt.evidenceTokenEstimate()));

            NormalizedDraft draft;
            ProposalDraft rawDraft = null;
            boolean fromModel = false;
            if (prompt.includedChunks().isEmpty()) {
                // Token optimisation + safety: nothing trustworthy to reason over, so do not call the model at all.
                draft = new NormalizedDraft(RootCauseCategory.UNKNOWN,
                    "No usable evidence was retrieved (none uploaded, none relevant, or all quarantined).",
                    "Upload evidence that explains the variance, then re-run the investigation.",
                    BigDecimal.ZERO.setScale(3), List.of());
            } else {
                try {
                    // Semantic cache replaces ONLY the model call; normalisation, citations and the gate still run.
                    Optional<SemanticCache.Hit> hit = cache.lookup(tenantId, prompt, job.getAnalystNote());
                    if (hit.isPresent()) {
                        rawDraft = hit.get().draft();
                        tx.executeWithoutResult(s -> llmCalls.save(new LlmCallRecord(tenantId, jobId,
                            "cache-" + hit.get().type().name().toLowerCase(java.util.Locale.ROOT), 0, 0, 0, "CACHE_HIT")));
                    } else {
                        rawDraft = aiClient.investigate(prompt, tenantId, jobId, job.getAnalystNote());
                        fromModel = true;
                    }
                    draft = InvestigationPromptBuilder.normalize(rawDraft);
                } catch (PromptBlockedException blocked) {
                    tx.executeWithoutResult(s -> llmCalls.save(new LlmCallRecord(tenantId, jobId, "blocked", 0, 0, 0, "BLOCKED")));
                    throw blocked;
                }
            }

            stage(jobId, tenantId, JobStage.VERIFYING, null);
            CitationResult citations = verifyRouteAndPersist(jobId, tenantId, breakItem, draft, prompt.includedChunkIds());
            if (fromModel && citations.valid()) {
                cache.store(tenantId, prompt, rawDraft); // only verified, grounded drafts are ever cached
            }
        } catch (Exception e) {
            log.warn("Investigation {} failed: {}", jobId, e.toString());
            if (e instanceof com.reconcileai.application.rero.ReroCallException reroFailure) {
                failRero(jobId, tenantId, reroFailure);
            } else if (e instanceof GatewayCallException gateway) {
                failJob(jobId, tenantId, GatewayFailure.reason(gateway), gateway);
            } else {
                failJob(jobId, tenantId, reasonOf(e));
            }
        }
    }

    /**
     * Platform Q1 pre-step: one EKA search for the job's tenant, before the model call. The outcome is stored and
     * audited (codes and counts only, never passage text) whatever it is. UNAVAILABLE continues with no knowledge
     * unless {@code KNOWLEDGE_FAIL_CLOSED=true}, which fails the job retryably (break FAILED_RETRYABLE, no proposal).
     * Skipped when there is no evidence, because then the model is not called at all.
     */
    private List<KnowledgeHit> consultKnowledge(UUID jobId, String tenantId, InvestigationJob job, BreakItem breakItem,
                                                List<RetrievedChunk> evidence) {
        if (knowledge == null) {
            return List.of();
        }
        // D1.2.12: a query built from this break's fields (masked, capped) unless switched back to the generic one.
        String query = breakSpecificQuery
            ? BreakKnowledgeQuery.build(breakItem, job.getAnalystNote(), evidence)
            : knowledgeQuery(job.getAnalystNote());
        KnowledgeResult raw = knowledge.search(tenantId, query);
        // D1.1.1: drop procedures unrelated to this break; only kept hits become refs and prompt text.
        var filtered = knowledgeFilter.apply(raw, breakContext(job.getAnalystNote(), evidence));
        KnowledgeResult result = filtered.result();
        tx.executeWithoutResult(s -> {
            InvestigationJob j = jobs.findByIdAndTenantId(jobId, tenantId).orElseThrow();
            j.recordKnowledge(result.outcome().name(), result.reason(), result.refs());
            jobs.save(j);
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("jobId", jobId.toString());
            details.put("outcome", result.outcome().name());
            if (result.reason() != null) {
                details.put("reason", result.reason());
            }
            details.put("procedures", result.hits().size());
            details.put("queryKind", breakSpecificQuery ? "BREAK_SPECIFIC" : "GENERIC");
            if (knowledgeFilter.settings().enabled() && raw.outcome() == KnowledgeOutcome.OK) {
                details.put("relevanceKept", filtered.kept());
                details.put("relevanceDropped", filtered.dropped());
            }
            audit.record(new AuditEntry(tenantId, "KNOWLEDGE_CONSULTED", "BREAK", j.getBreakItemId(), ACTOR, details));
        });
        if (result.outcome() == KnowledgeOutcome.UNAVAILABLE && knowledgeFailClosed) {
            throw new IllegalStateException("KNOWLEDGE_UNAVAILABLE");
        }
        return result.hits();
    }

    /** The break's own words (analyst note + retrieved evidence) used only to find candidate categories locally. */
    static String breakContext(String analystNote, List<RetrievedChunk> evidence) {
        StringBuilder sb = new StringBuilder(analystNote == null ? "" : analystNote);
        for (RetrievedChunk c : evidence) {
            sb.append(' ').append(c.text());
        }
        return sb.toString();
    }

    /**
     * Procedures are found by the kind of problem, not by this break's identifiers: no break reference or amounts go
     * to EKA. The analyst note is included and masked by the client before it leaves.
     */
    static String knowledgeQuery(String analystNote) {
        String base = "reconciliation break investigation procedure variance fee timing settlement fx duplicate";
        return analystNote == null || analystNote.isBlank() ? base : base + " " + analystNote;
    }

    /**
     * Agent mode: the model gathers evidence through read-only tools, then drafts. Only chunk ids that a search tool
     * returned during the run may be cited. Everything after the draft is shared with the single-call mode.
     */
    private void runAgent(UUID jobId, String tenantId, InvestigationJob job, BreakItem breakItem) {
        stage(jobId, tenantId, JobStage.CALLING_MODEL, null);
        InvestigationAgent.Result result = agent.run(tenantId, breakItem.getId(), jobId, job.getAnalystNote());
        List<String> snapshot = result.retrievedChunkIds();

        NormalizedDraft draft = InvestigationPromptBuilder.normalize(result.draft());
        if (snapshot.isEmpty()) {
            // Same rule as the single-call mode: nothing citable was found, so nothing is proposed.
            draft = new NormalizedDraft(RootCauseCategory.UNKNOWN,
                "The agent found no usable evidence (none uploaded, none relevant, or all quarantined).",
                "Upload evidence that explains the variance, then re-run the investigation.",
                BigDecimal.ZERO.setScale(3), List.of());
        }
        stage(jobId, tenantId, JobStage.VERIFYING, j -> j.recordRetrieval(snapshot, 0));
        verifyRouteAndPersist(jobId, tenantId, breakItem, draft, snapshot);
    }

    /**
     * Supervised modes: specialist agents draft, comment and review; the shared budget covers all of them. The
     * reviewer's verdict enters the policy gate as one boolean (rule 0, REVIEWER_CHALLENGE), which can only send the
     * break to a person. Risk flags are stored for the approver and never change the route.
     */
    private void runSupervised(UUID jobId, String tenantId, InvestigationJob job, BreakItem breakItem) {
        if (supervised == null) {
            throw new IllegalStateException("SUPERVISED_UNAVAILABLE");
        }
        stage(jobId, tenantId, JobStage.CALLING_MODEL, null);
        SupervisedInvestigation.Variant variant = job.getMode() == InvestigationMode.SUPERVISED_PLANNED
            ? SupervisedInvestigation.Variant.PLANNED : SupervisedInvestigation.Variant.FIXED;
        SupervisedInvestigation.Result result = supervised.run(variant, tenantId, breakItem.getId(), jobId, job.getAnalystNote());
        List<String> snapshot = result.retrievedChunkIds();

        NormalizedDraft draft = InvestigationPromptBuilder.normalize(result.draft());
        ReviewerVerdict verdict = result.verdict();
        if (snapshot.isEmpty()) {
            draft = new NormalizedDraft(RootCauseCategory.UNKNOWN,
                "The agents found no usable evidence (none uploaded, none relevant, or all quarantined).",
                "Upload evidence that explains the variance, then re-run the investigation.",
                BigDecimal.ZERO.setScale(3), List.of());
            verdict = ReviewerVerdict.notRun("NO_EVIDENCE");
        }
        ReviewerVerdict finalVerdict = verdict;
        stage(jobId, tenantId, JobStage.VERIFYING, j -> j.recordRetrieval(snapshot, 0));
        verifyRouteAndPersist(jobId, tenantId, breakItem, draft, snapshot, finalVerdict.challenged(),
            j -> j.recordReview(finalVerdict.verdict().name(), result.riskFlags()));
    }

    /**
     * RERO mode (platform Q3.5): RERO drafts through our internal read-only tools; we re-check its citations against
     * the chunk ids we served, then run the same citation check, policy gate and proposal path as every other mode.
     * A self-check CHALLENGE is the reviewerChallenged gate input (manual review); PASS adds nothing, so it can never
     * lower review. Any failure throws ReroCallException: job FAILED, break FAILED_RETRYABLE, no proposal.
     */
    private void runRero(UUID jobId, String tenantId, InvestigationJob job, BreakItem breakItem) {
        if (rero == null) {
            throw new com.reconcileai.application.rero.ReroCallException(
                com.reconcileai.application.rero.ReroCallException.DISABLED);
        }
        com.reconcileai.application.rero.ReroInvestigation.Outcome outcome = rero.run(job,
            new com.reconcileai.application.rero.ReroInvestigation.Listener() {
                @Override
                public void started(UUID runId) {
                    tx.executeWithoutResult(s -> {
                        InvestigationJob j = jobs.findByIdAndTenantId(jobId, tenantId).orElseThrow();
                        j.recordReroRun(runId, List.of());
                        jobs.save(j);
                    });
                }

                @Override
                public void stage(JobStage next) {
                    heartbeat(jobId, tenantId, next);
                }
            });
        List<String> snapshot = outcome.servedChunkIds();
        stage(jobId, tenantId, JobStage.VERIFYING, j -> j.recordRetrieval(snapshot, 0));
        verifyRouteAndPersist(jobId, tenantId, breakItem, outcome.draft(), snapshot, outcome.challenged(),
            j -> {
                j.recordReview(outcome.selfCheck().name(), List.of());
                j.recordReroRun(outcome.runId(), outcome.gatewayInvocationIds());
            });
    }

    /** A RERO failure: always retryable, no proposal, gateway invocation ids (if any) kept in the audit entry. */
    private void failRero(UUID jobId, String tenantId, com.reconcileai.application.rero.ReroCallException e) {
        List<String> invocations = e.gatewayInvocationIds();
        if (!invocations.isEmpty()) {
            tx.executeWithoutResult(s -> jobs.findByIdAndTenantId(jobId, tenantId).ifPresent(j -> {
                if (!j.isTerminal()) {
                    j.recordReroRun(j.getReroRunId(), invocations);
                    jobs.save(j);
                }
            }));
        }
        failJob(jobId, tenantId, e.code());
    }

    /**
     * Keeps a long RERO run alive for recovery (heartbeat) and moves the visible stage. Publishes only on a change.
     * No audit entry: INVESTIGATION_STARTED was written once by the first stage update.
     */
    private void heartbeat(UUID jobId, String tenantId, JobStage next) {
        JobStage[] before = new JobStage[1];
        InvestigationJob j = tx.execute(s -> {
            InvestigationJob job = jobs.findByIdAndTenantId(jobId, tenantId).orElseThrow();
            before[0] = job.getStage();
            job.advance(next);
            return jobs.save(job);
        });
        if (j != null && before[0] != j.getStage()) {
            progress.publish(j);
        }
    }

    private CitationResult verifyRouteAndPersist(UUID jobId, String tenantId, BreakItem breakItem, NormalizedDraft draft,
                                                 List<String> snapshotChunkIds) {
        return verifyRouteAndPersist(jobId, tenantId, breakItem, draft, snapshotChunkIds, false, null);
    }

    /** Citation check against the retrieval snapshot, policy gate, then proposal + job SUCCEEDED in one transaction. */
    private CitationResult verifyRouteAndPersist(UUID jobId, String tenantId, BreakItem breakItem, NormalizedDraft draft,
                                                 List<String> snapshotChunkIds, boolean reviewerChallenged,
                                                 java.util.function.Consumer<InvestigationJob> beforeSucceed) {
        CitationResult citations = citationVerifier.verify(draft.citedChunkIds(), snapshotChunkIds);
        // No-evidence path has no citations by construction; the UNKNOWN root cause routes it to manual review.
        boolean citationsOk = citations.valid() || draft.citedChunkIds().isEmpty() && draft.rootCause() == RootCauseCategory.UNKNOWN;
        stage(jobId, tenantId, JobStage.ROUTING, null);
        PolicyDecision decision = policyGate.evaluate(
            new PolicyInput(breakItem.variance(), draft.confidence(), draft.rootCause(), citationsOk, reviewerChallenged));

        InvestigationJob done = tx.execute(s -> {
            InvestigationJob j = jobs.findByIdAndTenantId(jobId, tenantId).orElseThrow();
            if (j.getStatus() != com.reconcileai.domain.JobStatus.RUNNING || !node.id().equals(j.getOwnerNode())) {
                // This node stalled past the lease and recovery already failed the job (possibly flagging an unknown
                // gateway outcome). Writing a proposal now would undo that and could race a newer job on the break.
                log.warn("Job {} is {} (owner {}); discarding the late result from {}", jobId, j.getStatus(),
                    j.getOwnerNode(), node.id());
                return j;
            }
            BreakItem b = breaks.findByIdAndTenantId(j.getBreakItemId(), tenantId).orElseThrow();
            ResolutionProposal p = proposalService.persistProposal(j, b, draft, citations.valid(),
                citations.invalidIds(), decision);
            if (beforeSucceed != null) {
                beforeSucceed.accept(j);
            }
            j.succeed(p.getId());
            return jobs.save(j);
        });
        progress.publish(done);
        return citations;
    }

    /** Marks a job FAILED and its break FAILED_RETRYABLE in one transaction. Idempotent for terminal jobs. */
    public void failJob(UUID jobId, String tenantId, String reason) {
        failJob(jobId, tenantId, reason, false, null);
    }

    /**
     * A gateway refusal (platform Q2.2 to Q2.5). Three shapes:
     * <ul>
     *   <li>outcome unknown (504, 409 unknown outcome, timeout after sending): job FAILED and flagged so the break
     *       cannot be re-run until a person reconciles it against the gateway ledger (Q2.3);</li>
     *   <li>policy block (RISK_BLOCKED, SENSITIVITY_BLOCKED): job FAILED with the reason, break to
     *       MANUAL_REVIEW_REQUIRED, because the same input would be refused again (Q2.5);</li>
     *   <li>anything else (budget, not-sent, schema, auth): job FAILED, break FAILED_RETRYABLE, no proposal (Q2.6).</li>
     * </ul>
     * In every case the audit entry carries the gateway's invocation id when it sent one.
     */
    public void failJob(UUID jobId, String tenantId, String reason, GatewayCallException gateway) {
        failJob(jobId, tenantId, reason, GatewayFailure.outcomeUnknown(gateway), GatewayFailure.policyBlocked(gateway),
            gateway.invocationId());
    }

    public void failJob(UUID jobId, String tenantId, String reason, boolean outcomeUnknown, UUID gatewayInvocationId) {
        failJob(jobId, tenantId, reason, outcomeUnknown, false, gatewayInvocationId);
    }

    /**
     * Same, when the gateway call may have run: the job is flagged outcome-unknown, which blocks a re-run until a person
     * reconciles it (platform Q2.3), and the audit entry carries the gateway's invocation id when it sent one.
     */
    @SuppressWarnings("java:S107")
    public void failJob(UUID jobId, String tenantId, String reason, boolean outcomeUnknown, boolean policyBlocked,
                        UUID gatewayInvocationId) {
        InvestigationJob failed = tx.execute(s -> {
            InvestigationJob j = jobs.findByIdAndTenantId(jobId, tenantId).orElseThrow();
            if (j.isTerminal()) {
                return j;
            }
            j.fail(reason);
            if (meters != null) {
                meters.counter("reconcile.investigation.failed", "reason", String.valueOf(reason)).increment();
            }
            breaks.findByIdAndTenantId(j.getBreakItemId(), tenantId).ifPresent(b -> {
                if (policyBlocked) {
                    b.markPolicyBlocked();
                } else {
                    b.markInvestigationFailed();
                }
                breaks.save(b);
            });
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("jobId", jobId.toString());
            details.put("reason", reason);
            if (outcomeUnknown) {
                j.markOutcomeUnknown();
                details.put("gatewayOutcomeUnknown", true);
            }
            if (policyBlocked) {
                details.put("gatewayPolicyBlocked", true);
                details.put("route", "MANUAL_REVIEW");
            }
            if (gatewayInvocationId != null) {
                details.put("gatewayInvocationId", gatewayInvocationId.toString());
            }
            audit.record(new AuditEntry(tenantId, "INVESTIGATION_FAILED", "BREAK", j.getBreakItemId(), ACTOR, details));
            return jobs.save(j);
        });
        progress.publish(failed);
    }

    private InvestigationJob stage(UUID jobId, String tenantId, JobStage next, java.util.function.Consumer<InvestigationJob> extra) {
        InvestigationJob j = tx.execute(s -> {
            InvestigationJob job = jobs.findByIdAndTenantId(jobId, tenantId).orElseThrow();
            job.advance(next); // also refreshes heartbeat, so a live job is never mistaken for an orphan
            if (extra != null) {
                extra.accept(job);
            }
            if (next == JobStage.RETRIEVING) {
                audit.record(new AuditEntry(tenantId, "INVESTIGATION_STARTED", "BREAK", job.getBreakItemId(), ACTOR,
                    Map.of("jobId", jobId.toString(), "node", node.id())));
            }
            return jobs.save(job);
        });
        progress.publish(j);
        return j;
    }

    /** Short, non-sensitive failure code for the job row (details stay in server logs). */
    private static String reasonOf(Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.matches("[A-Z_]{3,64}")) {
            return msg;
        }
        return "INTERNAL_ERROR: " + e.getClass().getSimpleName();
    }
}
