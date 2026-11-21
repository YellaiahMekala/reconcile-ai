// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.knowledge.KnowledgeOutcome;
import com.reconcileai.application.knowledge.KnowledgeResult;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.support.FakeEkaServer;
import com.reconcileai.support.FakeEkaServer.Doc;
import com.reconcileai.support.FakeEkaServer.Scripted;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT-4 follow-up: the EKA client retries HTTP 429 only (honours Retry-After, capped, jittered, at most 2 retries,
 * inside the per-lookup timeout). The sleeper and the random source are replaced, so no test waits.
 */
class EkaKnowledgeClientRetryTest {

    private static final String TENANT = "tenant-a";

    private FakeEkaServer eka;
    private final List<String> outcomes = new ArrayList<>();
    private final List<String> retryEvents = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void start() {
        eka = FakeEkaServer.start();
    }

    @AfterEach
    void stop() {
        eka.stop();
    }

    private EkaKnowledgeClient client(int retries, Duration maxWait, Duration jitter, Duration timeout, double random) {
        KnowledgeProperties props = new KnowledgeProperties(true, eka.baseUrl(), "unused", "aud-eka-api",
            Duration.ofSeconds(1), timeout, 3, 50, 120, false, 50, 10, Duration.ofSeconds(60), Map.of(),
            null, null, null, null, retries, maxWait, jitter);
        return new EkaKnowledgeClient(HttpClient.newHttpClient(), new ObjectMapper(), props, FakeEkaServer::jwtFor,
            new InjectionScanner(), (o, r) -> outcomes.add(o + ":" + r), retryEvents::add, sleeps::add, () -> random);
    }

    private EkaKnowledgeClient client() {
        return client(2, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(5), 0.0);
    }

    @Test
    void defaultsAreTwoRetriesTwoSecondCapAndQuarterSecondJitter() {
        KnowledgeProperties p = new KnowledgeProperties(true, "http://x", "u", "s", null, null, 0, 0, 0, false, 0, 0,
            null, null);
        assertThat(p.rateLimitRetries()).isEqualTo(2);
        assertThat(p.rateLimitMaxWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.rateLimitJitter()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void a429ThenA200ReturnsTheHitsAfterOneRetryAndWaitsRetryAfter() {
        Doc doc = Doc.of("fx.md", "FX breaks: compare the booking rate with the settlement rate.");
        eka.enqueue(Scripted.rateLimited("1"));
        eka.enqueue(Scripted.hits(doc));

        KnowledgeResult r = client().search(TENANT, "fx variance");

        assertThat(r.outcome()).isEqualTo(KnowledgeOutcome.OK);
        assertThat(eka.calls()).hasSize(2);
        assertThat(sleeps).containsExactly(1000L);                     // Retry-After 1 s, jitter 0.0
        assertThat(retryEvents).containsExactly("retry");
        assertThat(outcomes).containsExactly("OK:none");               // one outcome per lookup, no RATE_LIMITED
    }

    @Test
    void a429ForeverStopsAfterTwoRetriesAndRecordsRateLimitedOnce() {
        for (int i = 0; i < 5; i++) {
            eka.enqueue(Scripted.rateLimited("1"));
        }

        KnowledgeResult r = client().search(TENANT, "q");

        assertThat(r.outcome()).isEqualTo(KnowledgeOutcome.UNAVAILABLE);
        assertThat(r.reason()).isEqualTo("RATE_LIMITED");
        assertThat(eka.calls()).hasSize(3);                            // 1 call + 2 retries
        assertThat(sleeps).hasSize(2);
        assertThat(retryEvents).containsExactly("retry", "retry", "exhausted");
        assertThat(outcomes).containsExactly("UNAVAILABLE:RATE_LIMITED");
    }

    @Test
    void theWaitIsCappedWhateverRetryAfterSaysAndJitterStaysInBounds() {
        eka.enqueue(Scripted.rateLimited("30"));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));
        client().search(TENANT, "q");
        assertThat(sleeps).containsExactly(2000L);                     // 30 s asked, 2 s cap

        sleeps.clear();
        eka.enqueue(Scripted.rateLimited("1"));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));
        client(2, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(5), 0.999999).search(TENANT, "q");
        assertThat(sleeps).singleElement().satisfies(w -> assertThat(w).isBetween(1000L, 1250L));

        sleeps.clear();
        eka.enqueue(Scripted.rateLimited("1.9"));                      // 1.9 s + up to 250 ms jitter still capped at 2 s
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));
        client(2, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(5), 0.999999).search(TENANT, "q");
        assertThat(sleeps).containsExactly(2000L);
    }

    @Test
    void aMissingOrUnparseableRetryAfterWaitsAHalfSecondPlusJitter() {
        eka.enqueue(Scripted.status(429));
        eka.enqueue(Scripted.rateLimited("Wed, 21 Oct 2026 07:28:00 GMT"));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));

        client(2, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(5), 0.4).search(TENANT, "q");

        assertThat(sleeps).containsExactly(600L, 600L);                // 500 + 0.4 * 250
    }

    @Test
    void jitterDiffersBetweenParallelLookups() {
        long a = waitFor(0.1);
        long b = waitFor(0.9);
        assertThat(a).isNotEqualTo(b);
    }

    private long waitFor(double random) {
        sleeps.clear();
        eka.enqueue(Scripted.rateLimited("1"));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));
        client(2, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(5), random).search(TENANT, "q");
        return sleeps.get(0);
    }

    @Test
    void aWaitThatDoesNotFitInTheLookupTimeoutIsSkipped() {
        eka.enqueue(Scripted.rateLimited("2"));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));

        KnowledgeResult r = client(2, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofMillis(1500), 0.0)
            .search(TENANT, "q");

        assertThat(r.reason()).isEqualTo("RATE_LIMITED");
        assertThat(sleeps).isEmpty();
        assertThat(eka.calls()).hasSize(1);
        assertThat(retryEvents).containsExactly("exhausted");
    }

    @Test
    void retriesZeroTurnsRetryingOff() {
        eka.enqueue(Scripted.rateLimited("1"));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));

        KnowledgeResult r = client(0, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(5), 0.0)
            .search(TENANT, "q");

        assertThat(r.reason()).isEqualTo("RATE_LIMITED");
        assertThat(eka.calls()).hasSize(1);
        assertThat(retryEvents).isEmpty();
    }

    @Test
    void notFoundIsNotRetried() {
        eka.enqueue(Scripted.status(404));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));

        KnowledgeResult r = client().search(TENANT, "q");

        assertThat(r.reason()).isEqualTo("HTTP_404");
        assertThat(eka.calls()).hasSize(1);
        assertThat(sleeps).isEmpty();
        assertThat(retryEvents).isEmpty();
    }

    @Test
    void serverErrorIsNotRetried() {
        eka.enqueue(Scripted.status(500));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));

        KnowledgeResult r = client().search(TENANT, "q");

        assertThat(r.reason()).isEqualTo("HTTP_500");
        assertThat(eka.calls()).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void timeoutIsNotRetried() {
        eka.enqueue(Scripted.slow(1500));
        eka.enqueue(Scripted.hits(Doc.of("a.md", "text")));

        KnowledgeResult r = client(2, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofMillis(300), 0.0)
            .search(TENANT, "q");

        assertThat(r.reason()).isEqualTo("TIMEOUT");
        assertThat(sleeps).isEmpty();
        assertThat(retryEvents).isEmpty();
    }

    @Test
    void theBreakerSeesOneFailurePerLookupNotOnePerAttempt() {
        EkaKnowledgeClient c = client();
        for (int i = 0; i < 3; i++) {
            eka.enqueue(Scripted.rateLimited("0"));
        }
        c.search(TENANT, "q");                                         // 3 attempts, 1 breaker record (window 10)
        assertThat(c.breaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(c.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
