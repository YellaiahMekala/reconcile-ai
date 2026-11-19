// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import com.reconcileai.support.ApiTestSupport;
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

    /**
     * The alert rules in docker/observability/alerts.yml query these exact series and labels. If a metric is renamed the
     * rule silently never fires, so this test pins the names: a failed job, a failed model call and the breaker.
     */
    @Test
    void seriesUsedByTheAlertRulesAreExposed() {
        String analyst = JwtTestSupport.bearer("maker-alerts", "TENANT_ALERTS", List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "30000.00", "29980.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-provider-outage.txt", "text/plain");
        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));
        assertThat(job.getString("status")).isEqualTo("FAILED");

        String body = given().when().get("/actuator/prometheus").then().statusCode(200).extract().asString();
        assertThat(body).contains(
            "reconcile_investigation_failed_total{application=\"reconcile-ai\",reason=\"LLM_UNAVAILABLE\"}",
            "reconcile_llm_errors_total{application=\"reconcile-ai\",exception=",
            "reconcile_llm_latency_seconds_count{application=\"reconcile-ai\",model=\"unknown\",outcome=\"ERROR\"}",
            "resilience4j_circuitbreaker_state{application=\"reconcile-ai\",name=\"investigation-llm\",state=\"open\"}");
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
