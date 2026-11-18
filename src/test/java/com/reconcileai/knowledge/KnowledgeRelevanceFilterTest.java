package com.reconcileai.knowledge;

import com.reconcileai.application.knowledge.KnowledgeHit;
import com.reconcileai.application.knowledge.KnowledgeOutcome;
import com.reconcileai.application.knowledge.KnowledgeRelevanceFilter;
import com.reconcileai.application.knowledge.KnowledgeRelevanceFilter.Filtered;
import com.reconcileai.application.knowledge.KnowledgeRelevanceFilter.Settings;
import com.reconcileai.application.knowledge.KnowledgeResult;
import com.reconcileai.domain.RootCauseCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform D1.1.1: the knowledge relevance filter, pure and deterministic. */
class KnowledgeRelevanceFilterTest {

    private static final String MISSING = "No corresponding entry in the ledger; the receipt was not recorded.";
    private static final String FEE = "The intermediary bank deducted a wire fee before crediting.";

    private static KnowledgeHit hit(String id, String file, String text, Double score) {
        return new KnowledgeHit("kb:" + id, "doc-" + id, file, text, score);
    }

    private static final KnowledgeHit FEE_PROC = hit("1", "fee-runbook.md", "Fee breaks: check the bank charges advice.", 0.8);
    private static final KnowledgeHit MISSING_PROC = hit("2", "missing.md", "Missing entry: post the unrecorded receipt.", 0.7);

    @Test
    void scoreBoundaryIsInclusive() {
        var f = new KnowledgeRelevanceFilter(new Settings(true, 0.5, false));
        KnowledgeResult r = KnowledgeResult.ok(List.of(hit("a", "a.md", "x", 0.5), hit("b", "b.md", "y", 0.4999)));

        Filtered out = f.apply(r, "");

        assertThat(out.result().refs()).containsExactly("kb:a");
        assertThat(out.kept()).isEqualTo(1);
        assertThat(out.dropped()).isEqualTo(1);
    }

    @Test
    void missingScorePassesOnlyWithAZeroFloor() {
        KnowledgeResult r = KnowledgeResult.ok(List.of(hit("a", "a.md", "x", null)));

        assertThat(new KnowledgeRelevanceFilter(new Settings(true, 0.0, false)).apply(r, "").result().outcome())
            .isEqualTo(KnowledgeOutcome.OK);
        assertThat(new KnowledgeRelevanceFilter(new Settings(true, 0.1, false)).apply(r, "").result().outcome())
            .isEqualTo(KnowledgeOutcome.EMPTY);
    }

    @Test
    void categoryMatchDropsAFeeProcedureForAMissingEntryBreak() {
        var f = new KnowledgeRelevanceFilter(new Settings(true, 0.0, true));

        Filtered out = f.apply(KnowledgeResult.ok(List.of(FEE_PROC, MISSING_PROC)), MISSING);

        assertThat(out.result().refs()).containsExactly("kb:2");
        assertThat(out.dropped()).isEqualTo(1);
    }

    @Test
    void categoryMatchKeepsAFeeProcedureForAFeeBreak() {
        var f = new KnowledgeRelevanceFilter(new Settings(true, 0.0, true));

        Filtered out = f.apply(KnowledgeResult.ok(List.of(FEE_PROC)), FEE);

        assertThat(out.result().outcome()).isEqualTo(KnowledgeOutcome.OK);
        assertThat(out.result().refs()).containsExactly("kb:1");
    }

    @Test
    void noCategorySignalInTheBreakKeepsEveryHit() {
        var f = new KnowledgeRelevanceFilter(new Settings(true, 0.0, true));

        Filtered out = f.apply(KnowledgeResult.ok(List.of(FEE_PROC, MISSING_PROC)), "Counterparty acknowledged the query.");

        assertThat(out.kept()).isEqualTo(2);
        assertThat(out.dropped()).isZero();
    }

    @Test
    void allDroppedIsEmptyWithReasonAndNoRefs() {
        var f = new KnowledgeRelevanceFilter(new Settings(true, 0.0, true));

        Filtered out = f.apply(KnowledgeResult.ok(List.of(FEE_PROC)), MISSING);

        assertThat(out.result().outcome()).isEqualTo(KnowledgeOutcome.EMPTY);
        assertThat(out.result().reason()).isEqualTo(KnowledgeRelevanceFilter.FILTERED_LOW_RELEVANCE);
        assertThat(out.result().refs()).isEmpty();
        assertThat(out.kept()).isZero();
        assertThat(out.dropped()).isEqualTo(1);
    }

    @Test
    void orderIsKept() {
        var f = new KnowledgeRelevanceFilter(new Settings(true, 0.3, false));
        KnowledgeResult r = KnowledgeResult.ok(List.of(hit("c", "c.md", "x", 0.9), hit("low", "l.md", "x", 0.1),
            hit("a", "a.md", "x", 0.4), hit("b", "b.md", "x", 0.6)));

        assertThat(f.apply(r, "").result().refs()).containsExactly("kb:c", "kb:a", "kb:b");
    }

    @Test
    void nonOkOutcomesAndTheOffSwitchPassUnchanged() {
        var on = new KnowledgeRelevanceFilter(new Settings(true, 0.9, true));
        KnowledgeResult empty = KnowledgeResult.empty();
        KnowledgeResult unavailable = KnowledgeResult.unavailable("TIMEOUT");
        assertThat(on.apply(empty, MISSING).result()).isSameAs(empty);
        assertThat(on.apply(unavailable, MISSING).result()).isSameAs(unavailable);

        KnowledgeResult ok = KnowledgeResult.ok(List.of(FEE_PROC));
        assertThat(new KnowledgeRelevanceFilter(Settings.OFF).apply(ok, MISSING).result()).isSameAs(ok);
    }

    @Test
    void candidateCategoriesComeFromTheBreaksOwnWordsOnly() {
        assertThat(KnowledgeRelevanceFilter.candidateCategories(MISSING)).containsExactly(RootCauseCategory.MISSING_ENTRY);
        assertThat(KnowledgeRelevanceFilter.candidateCategories(FEE)).containsExactly(RootCauseCategory.FEE_OR_CHARGE);
        assertThat(KnowledgeRelevanceFilter.candidateCategories("Funds settled on value date 2026-07-01"))
            .containsExactly(RootCauseCategory.TIMING_DIFFERENCE);
        assertThat(KnowledgeRelevanceFilter.candidateCategories("feedback coffee")).isEmpty(); // word boundaries
        assertThat(KnowledgeRelevanceFilter.candidateCategories(null)).isEmpty();
    }
}
