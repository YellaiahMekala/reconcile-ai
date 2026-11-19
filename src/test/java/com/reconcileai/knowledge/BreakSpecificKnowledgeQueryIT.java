// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.knowledge;

import com.reconcileai.support.AbstractKnowledgeIT;
import com.reconcileai.support.FakeEkaServer.Call;
import com.reconcileai.support.FakeEkaServer.Doc;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1.2.12 end to end: with the default settings the query EKA receives is built from this break (category hint,
 * variance words, evidence file name), carries no amount or break reference, and the audit records the query kind.
 */
class BreakSpecificKnowledgeQueryIT extends AbstractKnowledgeIT {

    @DynamicPropertySource
    static void knowledge(DynamicPropertyRegistry r) {
        register(r, false);
    }

    @Test
    void ekaReceivesABreakSpecificMaskedQuery() {
        eka.index(TENANT_A, Doc.of("fee-runbook.md", "Fee breaks: compare the bank charges advice with the ledger."));

        JsonPath job = investigate(TENANT_A, "/fixtures/evidence/fee-only.txt");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        List<String> bodies = eka.calls().stream().filter(c -> c.path().endsWith("/search")).map(Call::body).toList();
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0))
            .contains("fee or charge")
            .contains("variance small USD external lower than ledger")
            .contains("fee-only.txt")
            .doesNotContain("BRK-")
            .doesNotContain("30500").doesNotContain("30000");
        assertThat(knowledgeAudit(TENANT_A)).last().asString().contains("\"queryKind\":\"BREAK_SPECIFIC\"");
    }
}
