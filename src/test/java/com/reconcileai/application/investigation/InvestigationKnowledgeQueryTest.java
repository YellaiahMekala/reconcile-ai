// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationKnowledgeQueryTest {

    private static final String BASE = InvestigationJobRunner.knowledgeQuery(null);

    @Test
    void nullOrBlankNoteGivesTheBaseQuery() {
        assertThat(BASE).isNotBlank();
        assertThat(InvestigationJobRunner.knowledgeQuery("")).isEqualTo(BASE);
        assertThat(InvestigationJobRunner.knowledgeQuery("   \t\n")).isEqualTo(BASE);
    }

    @Test
    void noteIsAppendedToTheBaseQuery() {
        assertThat(InvestigationJobRunner.knowledgeQuery("wire fee dispute")).isEqualTo(BASE + " wire fee dispute");
    }

    @Test
    void baseQueryCarriesNoBreakIdentifiers() {
        assertThat(BASE).doesNotContainPattern("\\d");
    }
}
