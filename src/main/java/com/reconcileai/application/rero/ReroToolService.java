package com.reconcileai.application.rero;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.application.knowledge.KnowledgeHit;
import com.reconcileai.application.knowledge.KnowledgeResult;
import com.reconcileai.application.knowledge.KnowledgeSearchPort;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.BreakStatus;
import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.InvestigationMode;
import com.reconcileai.domain.JobStatus;
import com.reconcileai.domain.ProposalStatus;
import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder;
import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.InvestigationJobRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import com.reconcileai.infrastructure.rero.ReroProperties;
import com.reconcileai.infrastructure.security.TenantScope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

/**
 * The four read-only tools behind {@code /internal/rero/v1/jobs/{jobId}/...} (platform Q3.2, Contract C section 2).
 *
 * <h2>Per call</h2>
 * <ol>
 *   <li>path job id must equal the token's {@code job_id}                       -> 403 RUN_TOKEN_JOB_MISMATCH</li>
 *   <li>the job must exist for the token's tenant                              -> 403 RUN_TOKEN_JOB_MISMATCH</li>
 *   <li>mode RERO and status RUNNING (server-side invalidation)                -> 409 JOB_NOT_RUNNING</li>
 *   <li>one atomic conditional UPDATE counts the call against the job's budget -> 429 TOOL_BUDGET_EXCEEDED</li>
 *   <li>the tool runs in the token tenant's scope (RLS), output masked and injection-screened</li>
 *   <li>the call is audited without its arguments; served evidence chunk ids are recorded for the citation check</li>
 * </ol>
 * No method takes a tenant or break id from the request: both come from the token and the job row. Nothing here
 * writes business data; the only writes are the counter, the served-chunk set and the audit rows.
 */
@Service
public class ReroToolService {

    private static final Logger log = LoggerFactory.getLogger(ReroToolService.class);
    private static final String ACTOR = "system:rero-run-token";
    private static final int MAX_QUERY_CHARS = 300;
    private static final int SIMILAR_LIMIT = 5;
    static final String WITHHELD = "[withheld: looked like an instruction, not data]";

    private final InvestigationJobRepository jobs;
    private final BreakItemRepository breaks;
    private final ResolutionProposalRepository proposals;
    private final HybridRetriever retriever;
    private final InjectionScanner scanner;
    private final ReroRunStore store;
    private final AuditRecorder audit;
    private final ReroProperties props;
    private final ObjectProvider<KnowledgeSearchPort> knowledge;
    private final ObjectProvider<MeterRegistry> meters;

    @SuppressWarnings("java:S107")
    public ReroToolService(InvestigationJobRepository jobs, BreakItemRepository breaks,
                           ResolutionProposalRepository proposals, HybridRetriever retriever, InjectionScanner scanner,
                           ReroRunStore store, AuditRecorder audit, ReroProperties props,
                           ObjectProvider<KnowledgeSearchPort> knowledge, ObjectProvider<MeterRegistry> meters) {
        this.jobs = jobs;
        this.breaks = breaks;
        this.proposals = proposals;
        this.retriever = retriever;
        this.scanner = scanner;
        this.store = store;
        this.audit = audit;
        this.props = props;
        this.knowledge = knowledge;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ responses (Contract C section 2)

    /** {@code sourceA} / {@code sourceB}: which side and its amount, e.g. {@code "ledger 30500"} (strings per schema). */
    public record BreakView(String breakRef, String variance, String currency, String sourceA, String sourceB,
                            String status) { }

    public record Chunk(String chunkId, String source, String text) { }

    public record EvidenceView(List<Chunk> chunks) { }

    public record SimilarItem(String breakRef, String rootCauseCategory, Instant resolvedAt) { }

    public record SimilarView(List<SimilarItem> items) { }

    public record Procedure(String ref, String title, String text) { }

    public record KnowledgeView(String outcome, List<Procedure> procedures) { }

    /** Identity from the validated run token; never from the request. */
    public record Caller(String tenantId, UUID tokenJobId) { }

    // ------------------------------------------------------------------ tools

    public BreakView breakSummary(Caller caller, UUID pathJobId) {
        return call(caller, pathJobId, "break", job -> {
            BreakItem b = breaks.findByIdAndTenantId(job.getBreakItemId(), job.getTenantId()).orElseThrow();
            return new Tool<>(new BreakView(safe(b.getBreakReference()), plain(b.variance().amount()),
                b.getCurrencyCode(), "ledger " + plain(b.getLedgerAmount()), "external " + plain(b.getExternalAmount()),
                b.getStatus().name()), 1, List.of());
        });
    }

    public EvidenceView evidence(Caller caller, UUID pathJobId, String query) {
        return call(caller, pathJobId, "evidence", job -> {
            BreakItem b = breaks.findByIdAndTenantId(job.getBreakItemId(), job.getTenantId()).orElseThrow();
            String q = query == null ? "" : query.strip();
            q = q.length() > MAX_QUERY_CHARS ? q.substring(0, MAX_QUERY_CHARS) : q;
            String effective = q.isBlank() ? InvestigationPromptBuilder.contextQuery(b, null) : q;
            List<RetrievedChunk> hits = retriever.retrieve(job.getTenantId(), b.getId(), effective);
            if (hits.isEmpty() && !q.isBlank()) {
                // Same widening as the agent tool: model words often are not in the files.
                hits = retriever.retrieve(job.getTenantId(), b.getId(),
                    q + " " + InvestigationPromptBuilder.contextQuery(b, null));
            }
            List<Chunk> out = new ArrayList<>();
            List<String> served = new ArrayList<>();
            for (RetrievedChunk c : hits) {
                if (scanner.isSuspect(c.text()) || scanner.isSuspect(String.valueOf(c.fileName()))) {
                    continue; // never served, so never citable
                }
                String text = PiiMasker.mask(c.text()).text();
                if (text.length() > props.maxEvidenceChars()) {
                    text = text.substring(0, props.maxEvidenceChars());
                }
                out.add(new Chunk(c.chunkId(), PiiMasker.mask(String.valueOf(c.fileName())).text(), text));
                served.add(c.chunkId());
            }
            return new Tool<>(new EvidenceView(out), out.size(), served);
        });
    }

    public SimilarView similarBreaks(Caller caller, UUID pathJobId) {
        return call(caller, pathJobId, "similar-breaks", job -> {
            List<ResolutionProposal> found = proposals.findRecentResolved(job.getTenantId(), ProposalStatus.APPROVED,
                job.getBreakItemId(), PageRequest.of(0, SIMILAR_LIMIT * 2));
            List<SimilarItem> items = new ArrayList<>();
            for (ResolutionProposal p : found) {
                if (items.size() >= SIMILAR_LIMIT) {
                    break;
                }
                breaks.findByIdAndTenantId(p.getBreakItemId(), job.getTenantId())
                    .filter(b -> b.getStatus() == BreakStatus.RESOLVED && b.getUpdatedAt() != null)
                    .ifPresent(b -> items.add(new SimilarItem(safe(b.getBreakReference()), p.getRootCause().name(),
                        b.getUpdatedAt())));
            }
            return new Tool<>(new SimilarView(items), items.size(), List.of());
        });
    }

    public KnowledgeView knowledge(Caller caller, UUID pathJobId, String query) {
        return call(caller, pathJobId, "knowledge", job -> {
            KnowledgeSearchPort port = knowledge.getIfAvailable();
            if (port == null) {
                return new Tool<>(new KnowledgeView("UNAVAILABLE", List.of()), 0, List.of());
            }
            String q = query == null ? "" : query.strip();
            q = q.length() > MAX_QUERY_CHARS ? q.substring(0, MAX_QUERY_CHARS) : q;
            // The EKA client masks the query and screens/masks every hit before returning it.
            KnowledgeResult result = port.search(job.getTenantId(),
                q.isBlank() ? "reconciliation break investigation procedure" : q);
            List<Procedure> procedures = new ArrayList<>();
            for (KnowledgeHit h : result.hits()) {
                procedures.add(new Procedure(h.ref(), safe(h.fileName()), safe(h.snippet())));
            }
            return new Tool<>(new KnowledgeView(result.outcome().name(), procedures), procedures.size(), List.of());
        });
    }

    // ------------------------------------------------------------------ guard

    /** What one tool produced: the response, a size for the audit row and evidence chunk ids served. */
    private record Tool<T>(T body, int items, List<String> servedChunkIds) { }

    private <T> T call(Caller caller, UUID pathJobId, String tool, Function<InvestigationJob, Tool<T>> body) {
        if (caller == null || caller.tokenJobId() == null || !caller.tokenJobId().equals(pathJobId)) {
            count(tool, "JOB_MISMATCH");
            throw ReroToolException.jobMismatch();
        }
        return TenantScope.call(caller.tenantId(), () -> {
            InvestigationJob job = jobs.findByIdAndTenantId(pathJobId, caller.tenantId()).orElse(null);
            if (job == null) {
                // A job of another tenant (or none): the token does not grant it. Same answer either way.
                count(tool, "JOB_MISMATCH");
                throw ReroToolException.jobMismatch();
            }
            if (job.getMode() != InvestigationMode.RERO || job.getStatus() != JobStatus.RUNNING) {
                count(tool, "JOB_NOT_RUNNING");
                throw ReroToolException.notRunning();
            }
            long started = System.nanoTime();
            OptionalInt seq = store.tryConsumeToolCall(caller.tenantId(), pathJobId);
            if (seq.isEmpty()) {
                // Either the budget is spent or the job left RUNNING between the read and the update.
                InvestigationJob now = jobs.findByIdAndTenantId(pathJobId, caller.tenantId()).orElse(job);
                if (now.getStatus() != JobStatus.RUNNING) {
                    count(tool, "JOB_NOT_RUNNING");
                    throw ReroToolException.notRunning();
                }
                auditCall(job, tool, "BUDGET_EXCEEDED", null);
                count(tool, "BUDGET_EXCEEDED");
                throw ReroToolException.budgetExceeded();
            }
            String outcome = "OK";
            Tool<T> result;
            try {
                result = body.apply(job);
            } catch (RuntimeException e) {
                outcome = "ERROR";
                log.warn("RERO tool {} failed for job {}: {}", tool, pathJobId, e.getClass().getSimpleName());
                store.recordToolCall(caller.tenantId(), pathJobId, seq.getAsInt(), tool, 0, outcome, elapsedMs(started));
                auditCall(job, tool, outcome, seq.getAsInt());
                count(tool, outcome);
                throw e;
            }
            if (!result.servedChunkIds().isEmpty()) {
                store.recordServedChunks(caller.tenantId(), pathJobId, result.servedChunkIds());
            }
            store.recordToolCall(caller.tenantId(), pathJobId, seq.getAsInt(), tool, result.items(), outcome,
                elapsedMs(started));
            auditCall(job, tool, outcome, seq.getAsInt());
            count(tool, outcome);
            return result.body();
        });
    }

    /** Audit chain entry with the tool name, sequence and outcome only: never the query or any returned text. */
    private void auditCall(InvestigationJob job, String tool, String outcome, Integer seq) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.getId().toString());
        details.put("tool", tool);
        details.put("outcome", outcome);
        if (seq != null) {
            details.put("seq", seq);
        }
        audit.record(new AuditEntry(job.getTenantId(), "RERO_TOOL_CALL", "BREAK", job.getBreakItemId(), ACTOR, details));
    }

    private void count(String tool, String outcome) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            registry.counter("reconcile.rero.tool.calls", "tool", tool, "outcome", outcome).increment();
        }
    }

    /** Masked, and withheld entirely if it reads like an instruction. */
    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return scanner.isSuspect(text) ? WITHHELD : PiiMasker.mask(text).text();
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
