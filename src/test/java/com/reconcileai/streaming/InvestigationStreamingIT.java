package com.reconcileai.streaming;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Increment 10 gate. Uses java.net.http (not RestAssured) because the response is a stream: the body completes
 * when the server completes the emitter, which happens on the job's terminal event.
 */
class InvestigationStreamingIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    final String analyst = JwtTestSupport.bearer("sse-a", "TENANT_SSE", List.of("RECON_ANALYST"));
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> subscribe(String bearer, String jobId) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/investigations/" + jobId + "/events"))
            .timeout(Duration.ofSeconds(30)).header("Accept", "text/event-stream").GET();
        if (bearer != null) {
            req.header("Authorization", bearer);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String startJob() {
        io.restassured.RestAssured.port = port;
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/bank-statement-timing.txt", "text/plain");
        return InvestigationTestSupport.start(analyst, breakId, null);
    }

    @Test
    void streamEmitsLifecycleEventsAndClosesOnTerminalState() throws Exception {
        String jobId = startJob();
        HttpResponse<String> res = subscribe(analyst, jobId);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/event-stream"));
        assertThat(res.body()).contains("event:progress").contains("\"status\":\"SUCCEEDED\"").contains("\"terminal\":true");
        // lifecycle metadata only: no model text is ever streamed
        assertThat(res.body()).doesNotContain("explanation").doesNotContain("recommendedAction");
    }

    @Test
    void subscribingAfterCompletionStillDeliversTheFinalSnapshot() throws Exception {
        String jobId = startJob();
        InvestigationTestSupport.awaitTerminal(analyst, jobId);
        HttpResponse<String> res = subscribe(analyst, jobId);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"stage\":\"COMPLETED\"");
    }

    @Test
    void foreignTenantGets404AndAnonymousGets401() throws Exception {
        String jobId = startJob();
        String foreign = JwtTestSupport.bearer("sse-x", "TENANT_SSE_OTHER", List.of("RECON_ANALYST", "SRE_ADMIN"));
        assertThat(subscribe(foreign, jobId).statusCode()).isEqualTo(404);
        assertThat(subscribe(null, jobId).statusCode()).isEqualTo(401);
    }
}
