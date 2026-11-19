// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.BreakSource;
import com.reconcileai.domain.Money;
import com.reconcileai.infrastructure.ai.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** D1.2.12: the EKA query is built from the break's own fields, masked, capped, and carries no amount or reference. */
class BreakKnowledgeQueryTest {

    private static BreakItem item(String ledger, String external) {
        Currency usd = Currency.getInstance("USD");
        return new BreakItem("T1", "BRK-SECRET-REF-42", new Money(new BigDecimal(ledger), usd),
            new Money(new BigDecimal(external), usd), BreakSource.REST);
    }

    private static RetrievedChunk chunk(String file, String text) {
        return new RetrievedChunk("c-" + UUID.randomUUID(), UUID.randomUUID(), 0, file, text, 0.9, 1, 0.5);
    }

    @Test
    void feeEvidenceGivesAFeeHintVarianceWordsAndSources() {
        String q = BreakKnowledgeQuery.build(item("30500.00", "30000.00"), null,
            List.of(chunk("bank-charges.txt", "The intermediary bank deducted a wire fee before crediting.")));
        assertThat(q).contains("fee or charge")
            .contains("variance small USD external lower than ledger")
            .contains("source rest")
            .contains("evidence bank-charges.txt")
            .doesNotContain("BRK-SECRET-REF-42")
            .doesNotContain("30500").doesNotContain("500");
    }

    @Test
    void twoBreaksOfDifferentKindsGetDifferentQueries() {
        String fee = BreakKnowledgeQuery.build(item("100", "90"), null, List.of(chunk("a.txt", "bank charges")));
        String missing = BreakKnowledgeQuery.build(item("100", "90"), null, List.of(chunk("a.txt", "not recorded in ledger")));
        assertThat(fee).isNotEqualTo(missing);
        assertThat(missing).contains("missing entry");
    }

    @Test
    void noCategorySignalFallsBackToTheGenericWords() {
        String q = BreakKnowledgeQuery.build(item("100", "100"), null, List.of());
        assertThat(q).contains(BreakKnowledgeQuery.GENERIC_HINTS).contains("no difference");
    }

    @Test
    void varianceBandsUseWordsNotFigures() {
        assertThat(BreakKnowledgeQuery.varianceWords(item("5000", "1000"))).startsWith("medium");
        assertThat(BreakKnowledgeQuery.varianceWords(item("1000", "90000"))).isEqualTo("material USD external higher than ledger");
    }

    @Test
    void noteIsMaskedAndTheQueryIsCapped() {
        String note = "call ops@example.com about account GB82WEST12345698765432 " + "x".repeat(1000);
        String q = BreakKnowledgeQuery.build(item("100", "90"), note, List.of());
        assertThat(q).doesNotContain("ops@example.com").doesNotContain("GB82WEST12345698765432");
        assertThat(q.length()).isLessThanOrEqualTo(BreakKnowledgeQuery.MAX_CHARS);
    }

    @Test
    void atMostThreeFileNames() {
        String q = BreakKnowledgeQuery.build(item("100", "90"), null,
            List.of(chunk("f1.txt", "a"), chunk("f2.txt", "b"), chunk("f3.txt", "c"), chunk("f4.txt", "d")));
        assertThat(q).contains("f1.txt f2.txt f3.txt").doesNotContain("f4.txt");
    }
}
