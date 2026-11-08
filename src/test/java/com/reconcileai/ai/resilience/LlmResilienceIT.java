package com.reconcileai.ai.resilience;

import com.reconcileai.infrastructure.ai.resilience.LlmResilience;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through the real job runner with mock-llm fault injection (see MockLlmChatModel):
 * a transient glitch is absorbed by the retry; a hard outage fails the job with a clear code and leaves the
 * break re-runnable (FAILED_RETRYABLE).
 */
class LlmResilienceIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    LlmResilience resilience;

    final String analyst = JwtTestSupport.bearer("maker-r", "TENANT_RESILIENCE", List.of("RECON_ANALYST"));

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        resilience.reset();
    }

    @AfterEach
    void tearDown() {
        resilience.reset(); // the breaker is shared by the cached context; never leak OPEN into other test classes
    }

    @Test
    void transientGlitchIsRetriedAndTheInvestigationSucceeds() {
        JsonPath p = InvestigationTestSupport.investigate(analyst, "30000.00", "29990.00",
            "/fixtures/evidence/timing-transient-glitch.txt");
        assertThat(p.getString("rootCause")).isEqualTo("TIMING_DIFFERENCE");
        assertThat(resilience.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void providerOutageFailsTheJobWithLlmUnavailableAndBreakIsRetryable() {
        String breakId = ApiTestSupport.createBreak(analyst, "30000.00", "29980.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-provider-outage.txt", "text/plain");

        JsonPath job = InvestigationTestSupport.awaitTerminal(analyst, InvestigationTestSupport.start(analyst, breakId, null));
        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("failureReason")).isEqualTo("LLM_UNAVAILABLE");

        String status = given().header("Authorization", analyst).when().get("/api/v1/breaks/" + breakId)
            .then().statusCode(200).extract().path("status");
        assertThat(status).isEqualTo("FAILED_RETRYABLE");
    }
}
