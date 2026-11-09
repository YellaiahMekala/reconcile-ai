package com.reconcileai.agent;

import com.reconcileai.infrastructure.ai.agent.AgentRunContext;
import com.reconcileai.infrastructure.ai.agent.ReconTools;
import com.reconcileai.infrastructure.ai.agent.ReconToolsFactory;
import com.reconcileai.infrastructure.security.TenantScope;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real model writes short queries, often numbers from the break summary that never appear in the files. The first
 * live agent run found no evidence at all because of this; the search tool now widens such a query with the break's
 * own context once.
 */
class AgentSearchQueryIT extends AbstractPostgresIT {

    static final String TENANT = "TENANT_AGENT_QUERY";

    @LocalServerPort
    int port;

    @Autowired
    ReconToolsFactory toolsFactory;

    final String analyst = JwtTestSupport.bearer("q-a", TENANT, List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private ReconTools tools(String tenant, String breakId) {
        return toolsFactory.create(new AgentRunContext(tenant, UUID.fromString(breakId), UUID.randomUUID(),
            Set.of("RECON_ANALYST"), Duration.ofMinutes(1), 20, 20));
    }

    @Test
    void naturalAndNumericQueriesBothReachTheEvidence() {
        String breakId = ApiTestSupport.createBreak(analyst, "5400.00", "4500.00");
        ApiTestSupport.upload(analyst, breakId, "/eval/evidence/dat-001-keying-note.txt", "text/plain");
        ReconTools tools = tools(TENANT, breakId);

        for (String q : List.of("data entry error", "transposed digits", "variance 900", "5400 4500 900 USD")) {
            assertThat(TenantScope.call(TENANT, () -> tools.searchEvidence(q))).as(q)
                .contains("<<<EVIDENCE chunk_id=").contains("dat-001-keying-note.txt");
        }
        assertThat(TenantScope.call(TENANT, () -> tools.searchEvidence("variance 900")))
            .startsWith("(Nothing matched your exact words");
    }

    @Test
    void theWidenedSearchStillStaysInsideTheTenant() {
        String breakId = ApiTestSupport.createBreak(analyst, "5400.00", "4500.00");
        ApiTestSupport.upload(analyst, breakId, "/eval/evidence/dat-001-keying-note.txt", "text/plain");
        ReconTools intruder = tools("TENANT_AGENT_QUERY_OTHER", breakId);

        assertThat(TenantScope.call("TENANT_AGENT_QUERY_OTHER", () -> intruder.searchEvidence("variance 900")))
            .startsWith("No matching evidence");
    }
}
