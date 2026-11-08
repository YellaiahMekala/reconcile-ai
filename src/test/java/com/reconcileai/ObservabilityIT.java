package com.reconcileai;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** /actuator/prometheus exposes the LLM cost, token, latency, PII and circuit-breaker series, and nothing sensitive. */
@AutoConfigureObservability(tracing = false) // Boot tests disable metric exporters unless asked
class ObservabilityIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    @Test
    void prometheusEndpointExposesLlmMetricsWithoutTenantData() {
        String analyst = JwtTestSupport.bearer("maker-o", "TENANT_OBS_SECRET", List.of("RECON_ANALYST"));
        InvestigationTestSupport.investigate(analyst, "50500.00", "50000.00", "/fixtures/evidence/bank-statement-timing.txt");

        String body = given().when().get("/actuator/prometheus").then().statusCode(200).extract().asString();
        assertThat(body).contains(
            "reconcile_llm_tokens_total",
            "reconcile_llm_cost_usd_total",
            "reconcile_llm_latency_seconds",
            "resilience4j_circuitbreaker_state",
            "http_server_requests_seconds");
        assertThat(body).doesNotContain("TENANT_OBS_SECRET");
    }

    @Test
    void demoLoginDoesNotExistWithoutItsProfile() {
        given().contentType("application/json").body("{\"persona\":\"analyst\"}").when().post("/dev/token")
            .then().statusCode(org.hamcrest.Matchers.not(200));
    }

    @Test
    void otherActuatorEndpointsStayClosed() {
        given().when().get("/actuator/env").then().statusCode(401);
    }
}
