package com.reconcileai.infrastructure.ai.agent;

import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.Evidence;
import com.reconcileai.domain.ProposalStatus;
import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.infrastructure.ai.agent.AgentRunContext.ToolCallTrace;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder;
import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.EvidenceRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The four read-only tools the investigation agent can call. One instance per run, bound to that run's
 * {@link AgentRunContext}: no method takes a tenant or break id, so the model can only look at the break it was
 * started for, in the tenant that started it. Nothing here writes to the database.
 *
 * <p>Every call goes through {@link #guarded}: call budget, deadline, role check, then the result is checked for
 * injected instructions, has account numbers, e-mails and similar masked, and is cut to a maximum length before the
 * model sees it. LangChain4j has no advisor chain, so this wrapper plays that part.
 */
public class ReconTools {

    private static final Logger log = LoggerFactory.getLogger(ReconTools.class);
    static final String REQUIRED_ROLE = "RECON_ANALYST";
    private static final int MAX_QUERY_CHARS = 300;
    private static final int SIMILAR_LIMIT = 5;

    private final AgentRunContext ctx;
    private final BreakItemRepository breaks;
    private final EvidenceRepository evidence;
    private final HybridRetriever retriever;
    private final ResolutionProposalRepository proposals;
    private final InjectionScanner scanner;
    private final MeterRegistry meters;
    private final int maxResultChars;

    ReconTools(AgentRunContext ctx, BreakItemRepository breaks, EvidenceRepository evidence, HybridRetriever retriever,
               ResolutionProposalRepository proposals, InjectionScanner scanner, MeterRegistry meters, int maxResultChars) {
        this.ctx = ctx;
        this.breaks = breaks;
        this.evidence = evidence;
        this.retriever = retriever;
        this.proposals = proposals;
        this.scanner = scanner;
        this.meters = meters;
        this.maxResultChars = maxResultChars;
    }

    @Tool("Get the reference, ledger amount, external amount, variance, currency and status of the break being investigated")
    public String getBreakSummary() {
        return guarded("getBreakSummary", "", () -> {
            BreakItem b = breaks.findByIdAndTenantId(ctx.breakId(), ctx.tenantId()).orElse(null);
            if (b == null) {
                return "NOT_FOUND: no such break";
            }
            return "reference=" + b.getBreakReference() + "\nledgerAmount=" + plain(b.getLedgerAmount())
                + "\nexternalAmount=" + plain(b.getExternalAmount()) + "\nvariance=" + plain(b.variance().amount())
                + "\ncurrency=" + b.getCurrencyCode() + "\nstatus=" + b.getStatus();
        });
    }

    @Tool("List the evidence files uploaded for this break: file name, processing status and number of text chunks")
    public String listEvidence() {
        return guarded("listEvidence", "", () -> {
            List<Evidence> files = evidence.findByBreakItemIdAndTenantIdOrderByCreatedAtAsc(ctx.breakId(), ctx.tenantId());
            if (files.isEmpty()) {
                return "No evidence has been uploaded for this break.";
            }
            StringBuilder out = new StringBuilder();
            for (Evidence e : files) {
                out.append(e.getFileName()).append(" status=").append(e.getStatus())
                    .append(" chunks=").append(e.getChunkCount() == null ? 0 : e.getChunkCount()).append('\n');
            }
            return out.toString();
        });
    }

    @Tool("Search this break's evidence for passages relevant to a question. Returns evidence blocks; cite them by chunk_id")
    public String searchEvidence(@P("what to look for, for example 'wire fee deducted' or 'settlement date'") String query) {
        String q = query == null ? "" : query.strip();
        String shown = q.length() > MAX_QUERY_CHARS ? q.substring(0, MAX_QUERY_CHARS) : q;
        return guarded("searchEvidence", shown, false, () -> {
            if (shown.isBlank()) {
                return "ERROR: query is empty";
            }
            List<RetrievedChunk> hits = retriever.retrieve(ctx.tenantId(), ctx.breakId(), shown);
            boolean widened = false;
            if (hits.isEmpty()) {
                // A model often searches with numbers or words that are not in the files ("variance 900").
                // Retry once with the same break context query the single-call mode uses, plus the model's words.
                BreakItem b = breaks.findByIdAndTenantId(ctx.breakId(), ctx.tenantId()).orElse(null);
                if (b != null) {
                    hits = retriever.retrieve(ctx.tenantId(), ctx.breakId(),
                        shown + " " + InvestigationPromptBuilder.contextQuery(b, null));
                    widened = !hits.isEmpty();
                }
            }
            log.debug("agent search job={} query='{}' hits={} widened={}", ctx.jobId(), shown, hits.size(), widened);
            StringBuilder out = new StringBuilder();
            if (widened) {
                out.append("(Nothing matched your exact words; these are the closest passages for this break.)\n");
            }
            for (RetrievedChunk c : hits) {
                if (scanner.isSuspect(c.text())) {
                    continue; // never shown to the model, and never citable
                }
                // Keep the text as the model will see it (masked), so a reviewer can check quotes against it.
                ctx.addRetrieved(c.chunkId(), PiiMasker.mask(c.text()).text());
                out.append(InvestigationPromptBuilder.evidenceBlock(c));
            }
            return hits.isEmpty() || out.indexOf("<<<EVIDENCE") < 0 ? "No matching evidence found for that query." : out.toString();
        });
    }

    @Tool("Find up to 5 earlier breaks of this tenant that were approved with a given root cause, with their variance band and action taken")
    public String findSimilarResolvedBreaks(
            @P("one of TIMING_DIFFERENCE, FX_RATE_DIFFERENCE, FEE_OR_CHARGE, DUPLICATE_ENTRY, MISSING_ENTRY, DATA_ENTRY_ERROR, PARTIAL_SETTLEMENT") String rootCause) {
        return guarded("findSimilarResolvedBreaks", String.valueOf(rootCause), () -> {
            RootCauseCategory category;
            try {
                category = RootCauseCategory.valueOf(String.valueOf(rootCause).strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return "ERROR: unknown root cause";
            }
            List<ResolutionProposal> found = proposals.findSimilarResolved(ctx.tenantId(), ProposalStatus.APPROVED,
                category, ctx.breakId(), PageRequest.of(0, SIMILAR_LIMIT));
            if (found.isEmpty()) {
                return "No earlier approved breaks with root cause " + category + ".";
            }
            StringBuilder out = new StringBuilder();
            for (ResolutionProposal p : found) {
                out.append("rootCause=").append(p.getRootCause()).append(" varianceBand=").append(band(p.getVarianceAmount()))
                    .append(" currency=").append(p.getCurrencyCode()).append(" action=")
                    .append(p.getRecommendedAction()).append('\n');
            }
            return out.toString();
        });
    }

    // ------------------------------------------------------------------ guard

    /** Budget, deadline, role, run, scan, mask, trim, record. Returns text for the model; never throws a tool error. */
    String guarded(String tool, String args, Supplier<String> body) {
        return guarded(tool, args, true, body);
    }

    /**
     * @param scanWhole false only for the search tool: it scans each chunk's own text before adding the evidence
     *                  delimiters, and scanning the assembled blocks would trip the delimiter rule on our own markup
     */
    String guarded(String tool, String args, boolean scanWhole, Supplier<String> body) {
        int count = ctx.nextToolCall();
        int seq = ctx.nextSeq();
        long started = System.nanoTime();
        ctx.checkDeadline();

        String outcome;
        String result;
        if (count > ctx.maxToolCalls()) {
            outcome = "LIMIT_REACHED";
            result = "LIMIT_REACHED: the tool-call budget is used up. Give your final JSON answer now.";
        } else if (!ctx.hasRole(REQUIRED_ROLE)) {
            outcome = "DENIED";
            result = "DENIED: this run is not allowed to use " + tool;
        } else {
            try {
                String raw = body.get();
                if (scanWhole && scanner.isSuspect(raw)) {
                    outcome = "INJECTION_FILTERED";
                    result = "[output withheld: it looked like an instruction to the model, not data]";
                } else {
                    outcome = "OK";
                    result = PiiMasker.mask(raw).text();
                    if (result.length() > maxResultChars) {
                        result = result.substring(0, maxResultChars) + "\n[truncated]";
                    }
                }
            } catch (RuntimeException e) {
                outcome = "ERROR";
                result = "ERROR: the tool failed";
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        ctx.record(new ToolCallTrace(seq, tool, sha256(args), result.length(), outcome, ms, ctx.currentAgent()));
        meters.counter("reconcile.agent.tool.calls", "tool", tool, "outcome", outcome).increment();
        return result;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String band(BigDecimal variance) {
        BigDecimal v = variance.abs();
        if (v.compareTo(BigDecimal.valueOf(1_000)) < 0) {
            return "under 1,000";
        }
        return v.compareTo(BigDecimal.valueOf(25_000)) < 0 ? "1,000 to 25,000" : "25,000 or more";
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
