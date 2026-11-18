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
