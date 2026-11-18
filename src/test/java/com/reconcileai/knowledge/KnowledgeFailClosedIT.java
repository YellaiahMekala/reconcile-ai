package com.reconcileai.knowledge;

import com.reconcileai.support.AbstractKnowledgeIT;
import com.reconcileai.support.FakeEkaServer.Scripted;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q1.4, {@code KNOWLEDGE_FAIL_CLOSED=true}: UNAVAILABLE fails the job retryably with no proposal and no model
 * call; EMPTY and REJECTED still continue (only unavailability is a failure).
 */
class KnowledgeFailClosedIT extends AbstractKnowledgeIT {

    @DynamicPropertySource
    static void knowledge(DynamicPropertyRegistry r) {
        register(r, true);
    }

    @Test
    void unavailableFailsTheJobRetryablyWithoutAProposal() {
        eka.enqueue(Scripted.status(503));
        String bearer = analyst(TENANT_A);
        String breakId = com.reconcileai.support.ApiTestSupport.createBreak(bearer, "30500.00", "30000.00");
        com.reconcileai.support.ApiTestSupport.upload(bearer, breakId, "/fixtures/evidence/timing-only.txt", "text/plain");
        String jobId = com.reconcileai.support.InvestigationTestSupport.start(bearer, breakId, null);
        JsonPath job = com.reconcileai.support.InvestigationTestSupport.awaitTerminal(bearer, jobId);

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("KNOWLEDGE_UNAVAILABLE");
        assertThat(job.getString("proposalId")).isNull();
        assertThat(job.getString("knowledgeOutcome")).isEqualTo("UNAVAILABLE");
        assertThat(breakStatus(TENANT_A, breakId)).isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_call WHERE job_id = ?::uuid", Integer.class, jobId))
            .as("no model call after a fail-closed knowledge outage").isZero();

        // EKA back: the ordinary re-run works.
        String rerun = com.reconcileai.support.InvestigationTestSupport.start(bearer, breakId, null);
        assertThat(com.reconcileai.support.InvestigationTestSupport.awaitTerminal(bearer, rerun).getString("status"))
            .isEqualTo("SUCCEEDED");
    }

    @Test
    void emptyStillContinuesWhenFailClosed() {
        JsonPath job = investigate(TENANT_A, "/fixtures/evidence/timing-only.txt");

        assertThat(job.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(job.getString("knowledgeOutcome")).isEqualTo("EMPTY");
    }
}
