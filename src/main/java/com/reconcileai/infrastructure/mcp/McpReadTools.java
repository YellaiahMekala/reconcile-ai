// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.mcp;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.ProposalStatus;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.infrastructure.ai.agent.ReconTools;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.EvidenceRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import com.reconcileai.infrastructure.security.CurrentUser;
import com.reconcileai.infrastructure.security.RateLimiter;
import com.reconcileai.infrastructure.security.Role;
import com.reconcileai.infrastructure.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * D1.2.2: the read-only tools exposed over MCP. See {@code docs/adr/ADR-mcp-read-only-server.md}.
 *
 * <p>Same rules as the investigation agent's {@link ReconTools} (and the same text, through its shared formatters):
 * <ul>
 *   <li>The tenant comes ONLY from the caller's validated JWT ({@link TenantContext}). No tool takes a tenant.</li>
 *   <li>Every query is tenant-scoped ({@code ...AndTenantId}) and Postgres RLS applies on top. Another tenant's id
 *       reads as NOT_FOUND.</li>
 *   <li>Nothing here writes business data. The only write is the audit event.</li>
 *   <li>Per call: role check, per-tenant rate limit, injection screen, PII mask, length cap, metric, audit. The audit
 *       records tool, outcome, result size and duration, never the arguments or the result.</li>
 * </ul>
 */
public class McpReadTools {

    static final Set<Role> ALLOWED = Set.of(Role.RECON_ANALYST, Role.APPROVER_L1, Role.APPROVER_L2);
    private static final int MAX_LIST = 20;
    private static final UUID NO_BREAK = new UUID(0, 0);

    private final TenantContext tenants;
    private final BreakItemRepository breaks;
    private final EvidenceRepository evidence;
    private final ResolutionProposalRepository proposals;
    private final InjectionScanner scanner;
    private final AuditRecorder audit;
    private final RateLimiter limiter;
    private final MeterRegistry meters;
    private final int maxResultChars;

    public McpReadTools(TenantContext tenants, BreakItemRepository breaks, EvidenceRepository evidence,
                        ResolutionProposalRepository proposals, InjectionScanner scanner, AuditRecorder audit,
                        RateLimiter limiter, MeterRegistry meters, int maxResultChars) {
        this.tenants = tenants;
        this.breaks = breaks;
        this.evidence = evidence;
        this.proposals = proposals;
        this.scanner = scanner;
        this.audit = audit;
        this.limiter = limiter;
        this.meters = meters;
        this.maxResultChars = maxResultChars;
    }

    @Tool(name = "get_break_summary", resultConverter = PlainTextResultConverter.class,
        description = "Reference, ledger amount, external amount, variance, currency and status of one break of your tenant")
    public String getBreakSummary(@ToolParam(description = "break id (UUID)") String breakId) {
        return call("get_break_summary", user -> {
            UUID id = parse(breakId);
            if (id == null) {
                return "ERROR: breakId must be a UUID";
            }
            return breaks.findByIdAndTenantId(id, user.tenantId())
                .map(ReconTools::breakSummaryText).orElse("NOT_FOUND: no such break");
        });
    }

    @Tool(name = "list_evidence", resultConverter = PlainTextResultConverter.class,
        description = "Evidence files of one break of your tenant: file name, processing status, number of text chunks")
    public String listEvidence(@ToolParam(description = "break id (UUID)") String breakId) {
        return call("list_evidence", user -> {
            UUID id = parse(breakId);
            if (id == null) {
                return "ERROR: breakId must be a UUID";
            }
            if (breaks.findByIdAndTenantId(id, user.tenantId()).isEmpty()) {
                return "NOT_FOUND: no such break";
            }
            return ReconTools.evidenceListText(evidence.findByBreakItemIdAndTenantIdOrderByCreatedAtAsc(id, user.tenantId()));
        });
    }

    @Tool(name = "list_recent_breaks", resultConverter = PlainTextResultConverter.class,
        description = "The most recent breaks of your tenant (newest first): id, reference, status, variance band, currency")
    public String listRecentBreaks(@ToolParam(description = "how many, 1 to 20", required = false) Integer limit) {
        return call("list_recent_breaks", user -> {
            int n = limit == null ? 10 : Math.max(1, Math.min(MAX_LIST, limit));
            List<BreakItem> found = breaks.findByTenantIdOrderByCreatedAtDesc(user.tenantId(), PageRequest.of(0, n));
            if (found.isEmpty()) {
                return "No breaks.";
            }
            StringBuilder out = new StringBuilder();
            for (BreakItem b : found) {
                out.append("id=").append(b.getId()).append(" reference=").append(b.getBreakReference())
                    .append(" status=").append(b.getStatus()).append(" varianceBand=")
                    .append(ReconTools.band(b.variance().amount())).append(" currency=").append(b.getCurrencyCode())
                    .append('\n');
            }
            return out.toString();
        });
    }

    @Tool(name = "find_similar_resolved_breaks", resultConverter = PlainTextResultConverter.class,
        description = "Up to 5 earlier approved breaks of your tenant with a given root cause: variance band and action taken")
    public String findSimilarResolvedBreaks(@ToolParam(description = "one of TIMING_DIFFERENCE, FX_RATE_DIFFERENCE, "
        + "FEE_OR_CHARGE, DUPLICATE_ENTRY, MISSING_ENTRY, DATA_ENTRY_ERROR, PARTIAL_SETTLEMENT") String rootCause) {
        return call("find_similar_resolved_breaks", user -> {
            RootCauseCategory category;
            try {
                category = RootCauseCategory.valueOf(String.valueOf(rootCause).strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return "ERROR: unknown root cause";
            }
            return ReconTools.similarResolvedText(category, proposals.findSimilarResolved(user.tenantId(),
                ProposalStatus.APPROVED, category, NO_BREAK, PageRequest.of(0, 5)));
        });
    }

    // ------------------------------------------------------------------ guard

    /** Thrown for DENIED and RATE_LIMITED: the MCP layer turns it into a tool error result. */
    public static class McpToolRefused extends RuntimeException {
        public McpToolRefused(String message) {
            super(message);
        }
    }

    private String call(String tool, Function<CurrentUser, String> body) {
        long started = System.nanoTime();
        CurrentUser user = tenants.currentUser(); // validated JWT only; throws when there is none
        String outcome;
        String result;
        RuntimeException refusal = null;
        if (user.roles().stream().noneMatch(ALLOWED::contains)) {
            outcome = "DENIED";
            result = "";
            refusal = new McpToolRefused("DENIED: your role may not use " + tool);
        } else if (!limiter.tryAcquire("mcp:" + user.tenantId()).allowed()) {
            outcome = "RATE_LIMITED";
            result = "";
            refusal = new McpToolRefused("RATE_LIMITED: too many tool calls for this tenant, retry later");
        } else {
            try {
                String raw = body.apply(user);
                if (scanner.isSuspect(raw)) {
                    outcome = "INJECTION_FILTERED";
                    result = "[output withheld: it looked like an instruction to a model, not data]";
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
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tool", tool);
        details.put("outcome", outcome);
        details.put("resultChars", result.length());
        details.put("durationMs", ms);
        audit.record(new AuditEntry(user.tenantId(), "MCP_TOOL_CALLED", "MCP_TOOL", null, user.subject(), details));
        meters.counter("reconcile.mcp.tool.calls", "tool", tool, "outcome", outcome).increment();
        if (refusal != null) {
            throw refusal;
        }
        return result;
    }

    private static UUID parse(String id) {
        try {
            return id == null ? null : UUID.fromString(id.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
