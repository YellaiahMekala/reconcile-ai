package com.reconcileai.knowledge;

import com.reconcileai.support.AbstractKnowledgeIT;
import com.reconcileai.support.FakeEkaServer.Doc;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform D1.1.1 end to end with the default relevance settings (filter on, category match on, min score 0.0): a
 * fee procedure never reaches the prompt of a missing-entry break, but does reach a fee break. All dropped means
 * EMPTY with reason FILTERED_LOW_RELEVANCE, no refs, the job still succeeds, and the audit holds counts only.
 */
class KnowledgeRelevanceIT extends AbstractKnowledgeIT {

    @DynamicPropertySource
    static void knowledge(DynamicPropertyRegistry r) {
        register(r, false);
    }

    private static Doc feeProcedure() {
        return Doc.of("fee-runbook.md", "Fee breaks: compare the bank charges advice with the ledger and book the fee.");
    }

    @Test
    void aFeeProcedureIsDroppedForAMissingEntryBreak() {
        eka.index(TENANT_A, feeProcedure());

        JsonPath job = investigate(TENANT_A, "/fixtures/evidence/missing-entry.txt");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getString("knowledgeOutcome")).isEqualTo("EMPTY");
        assertThat(job.getString("knowledgeReason")).isEqualTo("FILTERED_LOW_RELEVANCE");
        assertThat(job.getList("knowledgeRefs")).isEmpty();
        assertThat(knowledgeAudit(TENANT_A)).last().asString()
            .contains("\"outcome\":\"EMPTY\"").contains("\"reason\":\"FILTERED_LOW_RELEVANCE\"")
            .contains("\"relevanceKept\":0").contains("\"relevanceDropped\":1")
            .doesNotContain("bank charges advice");
    }

    @Test
    void aFeeProcedureIsKeptForAFeeBreak() {
        Doc fee = feeProcedure();
        eka.index(TENANT_A, fee);

        JsonPath job = investigate(TENANT_A, "/fixtures/evidence/fee-only.txt");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getString("knowledgeOutcome")).isEqualTo("OK");
        assertThat(job.getString("knowledgeReason")).isNull();
        assertThat(job.getList("knowledgeRefs", String.class)).containsExactly("kb:" + fee.chunkId());
        assertThat(knowledgeAudit(TENANT_A)).last().asString()
            .contains("\"relevanceKept\":1").contains("\"relevanceDropped\":0");
    }

    @Test
    void onlyRelevantHitsBecomeRefsWhenSomeAreDropped() {
        Doc fee = feeProcedure();
        Doc missing = Doc.of("missing-entry.md", "Missing entry: find the unrecorded receipt and post it.");
        eka.index(TENANT_A, fee);
        eka.index(TENANT_A, missing);

        JsonPath job = investigate(TENANT_A, "/fixtures/evidence/missing-entry.txt");

        assertThat(job.getString("knowledgeOutcome")).isEqualTo("OK");
        assertThat(job.getList("knowledgeRefs", String.class)).containsExactly("kb:" + missing.chunkId());
    }
}
