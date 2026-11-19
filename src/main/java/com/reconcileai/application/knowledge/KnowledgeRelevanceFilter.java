// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.knowledge;

import com.reconcileai.domain.RootCauseCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Platform D1.1.1: keeps only procedure hits that are relevant to the break being investigated.
 * <p>
 * Why: the Q4.3 ablation showed the knowledge pre-step lowered route accuracy because procedures for an unrelated
 * category (fee runbooks on missing-entry and timing breaks) reached the prompt. A hit is kept only if:
 * <ol>
 *   <li><b>score</b>: EKA's {@code scores.cosine} is at least {@code minScore}. A hit without a score passes only
 *       when {@code minScore <= 0}.</li>
 *   <li><b>category match</b> (when on): the hit's file name or text mentions a term of at least one <i>candidate
 *       category</i>. Candidate categories are found deterministically from the analyst note and the retrieved break
 *       evidence (not from the generic knowledge query). If no candidate category is found, the check cannot judge and
 *       the hit passes (conservative: it never removes a procedure without a signal).</li>
 * </ol>
 * Order is kept (EKA's best-first). Only {@link KnowledgeOutcome#OK} results are filtered; other outcomes pass
 * unchanged. If every hit is dropped the result is {@link KnowledgeOutcome#EMPTY} with reason
 * {@value #FILTERED_LOW_RELEVANCE}. Pure and deterministic: no model, no network.
 */
public final class KnowledgeRelevanceFilter {

    public static final String FILTERED_LOW_RELEVANCE = "FILTERED_LOW_RELEVANCE";

    /** Lower-case terms per category, matched on word boundaries. Deliberately short and specific. */
    static final Map<RootCauseCategory, List<String>> TERMS = terms();

    /**
     * @param enabled       master switch ({@code reconcile.knowledge.relevance-filter-enabled}); false keeps every hit
     * @param minScore      {@code reconcile.knowledge.min-score}, compared with EKA's cosine score
     * @param categoryMatch {@code reconcile.knowledge.category-match}
     */
    public record Settings(boolean enabled, double minScore, boolean categoryMatch) {
        public static final Settings OFF = new Settings(false, 0.0, false);
    }

    /** The filtered result plus counts for the audit event (never text). */
    public record Filtered(KnowledgeResult result, int kept, int dropped) { }

    private final Settings settings;

    public KnowledgeRelevanceFilter(Settings settings) {
        this.settings = settings == null ? Settings.OFF : settings;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * @param result       the lookup result
     * @param breakContext analyst note and retrieved evidence text for this break; only used to find candidate
     *                     categories, never sent anywhere
     */
    public Filtered apply(KnowledgeResult result, String breakContext) {
        if (!settings.enabled() || result.outcome() != KnowledgeOutcome.OK) {
            return new Filtered(result, result.hits().size(), 0);
        }
        Set<RootCauseCategory> candidates = settings.categoryMatch()
            ? candidateCategories(breakContext) : EnumSet.noneOf(RootCauseCategory.class);
        List<KnowledgeHit> kept = new ArrayList<>();
        for (KnowledgeHit hit : result.hits()) {
            if (passesScore(hit) && passesCategory(hit, candidates)) {
                kept.add(hit);
            }
        }
        int dropped = result.hits().size() - kept.size();
        if (kept.isEmpty()) {
            return new Filtered(KnowledgeResult.filteredOut(), 0, dropped);
        }
        return new Filtered(dropped == 0 ? result : KnowledgeResult.ok(kept), kept.size(), dropped);
    }

    private boolean passesScore(KnowledgeHit hit) {
        if (hit.score() == null) {
            return settings.minScore() <= 0.0;
        }
        return hit.score() >= settings.minScore();
    }

    private boolean passesCategory(KnowledgeHit hit, Set<RootCauseCategory> candidates) {
        if (!settings.categoryMatch() || candidates.isEmpty()) {
            return true;
        }
        String text = normalise((hit.fileName() == null ? "" : hit.fileName().replace('-', ' ').replace('_', ' '))
            + " " + (hit.snippet() == null ? "" : hit.snippet()));
        for (RootCauseCategory c : candidates) {
            if (mentionsAny(text, TERMS.get(c))) {
                return true;
            }
        }
        return false;
    }

    /** Categories whose terms appear in the break's own context (analyst note, evidence). */
    public static Set<RootCauseCategory> candidateCategories(String breakContext) {
        Set<RootCauseCategory> found = EnumSet.noneOf(RootCauseCategory.class);
        if (breakContext == null || breakContext.isBlank()) {
            return found;
        }
        String text = normalise(breakContext);
        TERMS.forEach((category, terms) -> {
            if (mentionsAny(text, terms)) {
                found.add(category);
            }
        });
        return found;
    }

    private static boolean mentionsAny(String normalisedText, List<String> terms) {
        for (String term : terms) {
            if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])").matcher(normalisedText).find()) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Map<RootCauseCategory, List<String>> terms() {
        Map<RootCauseCategory, List<String>> m = new EnumMap<>(RootCauseCategory.class);
        m.put(RootCauseCategory.TIMING_DIFFERENCE, List.of("timing", "value date", "cut-off", "cutoff",
            "business day", "next day", "month end", "in transit"));
        m.put(RootCauseCategory.FX_RATE_DIFFERENCE, List.of("fx", "exchange rate", "currency conversion",
            "conversion rate", "revaluation"));
        m.put(RootCauseCategory.FEE_OR_CHARGE, List.of("fee", "fees", "charge", "charges", "commission",
            "bank charges"));
        m.put(RootCauseCategory.DUPLICATE_ENTRY, List.of("duplicate", "duplicated", "posted twice",
            "double posting", "double posted"));
        m.put(RootCauseCategory.MISSING_ENTRY, List.of("missing", "missing entry", "not recorded", "unrecorded",
            "not posted", "no corresponding", "omitted"));
        m.put(RootCauseCategory.DATA_ENTRY_ERROR, List.of("typo", "keying error", "data entry", "transposed",
            "transposition", "mis-keyed"));
        m.put(RootCauseCategory.PARTIAL_SETTLEMENT, List.of("partial", "partially", "part payment", "instalment",
            "installment", "remaining balance", "short payment"));
        return Map.copyOf(m);
    }
}
