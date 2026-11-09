package com.reconcileai.agent;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/** The shipped default: agent mode is off, a request for it is refused, and single-call keeps working. */
@TestPropertySource(properties = "reconcile.agent.enabled=false")
class AgentDisabledByDefaultIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    final String analyst = JwtTestSupport.bearer("off-a", "TENANT_AGENT_OFF", List.of("RECON_ANALYST"));

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void agentModeIsRefusedWhenNotEnabledAndNothingChanges() {
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");

        given().header("Authorization", analyst).contentType(ContentType.JSON).body("{\"mode\":\"AGENT\"}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(400).body("code", equalTo("INPUT_REJECTED"));
        given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().body("status", equalTo("READY_TO_INVESTIGATE"));

        // the ordinary mode on the same break still works
        String job = InvestigationTestSupport.start(analyst, breakId, null);
        assertThat(InvestigationTestSupport.awaitTerminal(analyst, job).getString("status")).isEqualTo("SUCCEEDED");
    }
}
