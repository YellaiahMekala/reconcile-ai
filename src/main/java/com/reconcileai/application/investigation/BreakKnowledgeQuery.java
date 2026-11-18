package com.reconcileai.application.investigation;

import com.reconcileai.application.knowledge.KnowledgeRelevanceFilter;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Platform D1.2.12: the EKA query built from this break's own fields instead of one generic query for every break.
 * <p>
 * Why: with one generic query EKA's cosine score is almost constant per corpus, so the D1.1.1 score floor cannot tell
 * one break from another (see {@code docs/proof/pq1-2-4-relevance-filter-remeasure-2026-11-18.md}, section 4).
 * <p>
 * What goes in (in this order, so the cap cuts the least useful part last):
 * <ol>
 *   <li>category hints: the categories whose terms appear in the analyst note or retrieved evidence (same term list as
 *       {@link KnowledgeRelevanceFilter}); the generic category words when none is found</li>
 *   <li>variance as words: size band, currency and which side is higher. Never the amount itself</li>
 *   <li>sources: intake channel and up to three evidence file names</li>
 *   <li>the analyst note</li>
 * </ol>
 * The whole query is PII-masked here (the EKA client masks again) and cut to {@value #MAX_CHARS} characters. No break
 * reference, id or amount is sent. Pure and deterministic.
 */
public final class BreakKnowledgeQuery {

    static final int MAX_CHARS = 400;
    private static final int MAX_FILES = 3;
    static final String GENERIC_HINTS = "variance fee timing settlement fx duplicate";

    private BreakKnowledgeQuery() {}

    public static String build(BreakItem item, String analystNote, List<RetrievedChunk> evidence) {
        List<RetrievedChunk> chunks = evidence == null ? List.of() : evidence;
        StringBuilder q = new StringBuilder("reconciliation break investigation procedure for ");
        q.append(categoryHints(InvestigationJobRunner.breakContext(analystNote, chunks)));
        if (item != null) {
            q.append("; variance ").append(varianceWords(item));
            if (item.getSource() != null) {
                q.append("; source ").append(item.getSource().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
        }
        Set<String> files = new LinkedHashSet<>();
        for (RetrievedChunk c : chunks) {
            if (c.fileName() != null && !c.fileName().isBlank() && files.size() < MAX_FILES) {
                files.add(c.fileName().strip());
            }
        }
        if (!files.isEmpty()) {
            q.append("; evidence ").append(String.join(" ", files));
        }
        if (analystNote != null && !analystNote.isBlank()) {
            q.append("; note ").append(analystNote.strip().replaceAll("\\s+", " "));
        }
        String masked = PiiMasker.mask(q.toString()).text();
        return masked.length() > MAX_CHARS ? masked.substring(0, MAX_CHARS) : masked;
    }

    static String categoryHints(String breakContext) {
        Set<RootCauseCategory> found = KnowledgeRelevanceFilter.candidateCategories(breakContext);
        if (found.isEmpty()) {
            return GENERIC_HINTS;
        }
        List<String> words = found.stream().map(c -> c.name().toLowerCase(Locale.ROOT).replace('_', ' ')).toList();
        return String.join(", ", words);
    }

    /** "small USD, external lower than ledger": size band in words, never the figure. */
    static String varianceWords(BreakItem item) {
        BigDecimal ledger = item.getLedgerAmount();
        BigDecimal external = item.getExternalAmount();
        if (ledger == null || external == null) {
            return "unknown";
        }
        BigDecimal diff = ledger.subtract(external).abs();
        String band = diff.compareTo(BigDecimal.valueOf(1_000)) < 0 ? "small"
            : diff.compareTo(BigDecimal.valueOf(25_000)) < 0 ? "medium" : "material";
        int cmp = external.compareTo(ledger);
        String side = cmp < 0 ? "external lower than ledger" : cmp > 0 ? "external higher than ledger" : "no difference";
        return band + " " + (item.getCurrencyCode() == null ? "" : item.getCurrencyCode() + " ") + side;
    }
}
