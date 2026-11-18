package com.reconcileai.knowledge;

import com.reconcileai.support.AbstractKnowledgeIT;
import com.reconcileai.support.FakeEkaServer.Call;
import com.reconcileai.support.FakeEkaServer.Doc;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** D1.2.12 off-switch: {@code KNOWLEDGE_BREAK_SPECIFIC_QUERY=false} sends the old generic query. */
class GenericKnowledgeQuerySwitchIT extends AbstractKnowledgeIT {

    @DynamicPropertySource
    static void knowledge(DynamicPropertyRegistry r) {
        register(r, false);
        r.add("reconcile.knowledge.break-specific-query", () -> "false");
    }

    @Test
    void switchOffSendsTheGenericQuery() {
        eka.index(TENANT_A, Doc.of("fee-runbook.md", "Fee breaks: compare the bank charges advice with the ledger."));

        investigate(TENANT_A, "/fixtures/evidence/fee-only.txt");

        List<String> bodies = eka.calls().stream().filter(c -> c.path().endsWith("/search")).map(Call::body).toList();
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("reconciliation break investigation procedure variance fee timing settlement fx duplicate")
            .doesNotContain("external lower than ledger");
        assertThat(knowledgeAudit(TENANT_A)).last().asString().contains("\"queryKind\":\"GENERIC\"");
    }
}
