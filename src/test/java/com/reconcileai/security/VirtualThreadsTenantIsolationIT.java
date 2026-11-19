// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.security;

import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation under Java 21 virtual threads (ADR-virtual-threads).
 *
 * <p>With {@code spring.threads.virtual.enabled=true} Tomcat runs each request on a new virtual thread. The risk is a
 * stale identity: SecurityContextHolder and TenantScope are thread-local, so a thread that served tenant A must not
 * answer tenant B with A's data, and a thread created during a request must not inherit the request's identity.
 * This test fires interleaved requests for two fresh tenants and checks every response holds only its own rows.
 * It also proves the flag is in effect, because a test that passed on platform threads would prove nothing.
 *
 * <p>Release-blocking, like {@code SecurityAndTenantIsolationIT}: do not weaken.
 */
@Import(VirtualThreadsTenantIsolationIT.ProbeConfig.class)
class VirtualThreadsTenantIsolationIT extends AbstractPostgresIT {

    private static final int REQUESTS_PER_TENANT = 60;

    @LocalServerPort
    int port;

    @Autowired
    RequestThreadProbe probe;

    @Autowired
    Environment env;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
        probe.reset();
    }

    @Test
    void interleavedRequestsOnVirtualThreadsNeverSeeAnotherTenantsBreaks() throws Exception {
        assertThat(env.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();

        String tenantA = "VT-A-" + UUID.randomUUID();
        String tenantB = "VT-B-" + UUID.randomUUID();
        String analystA = JwtTestSupport.bearer("analyst-a", tenantA, List.of("RECON_ANALYST"));
        String analystB = JwtTestSupport.bearer("analyst-b", tenantB, List.of("RECON_ANALYST"));

        String refA = createBreak(analystA, "VT-A-REF");
        String refB = createBreak(analystB, "VT-B-REF");

        // Interleave A and B on many platform client threads so the server handles them concurrently.
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService clients = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < REQUESTS_PER_TENANT; i++) {
                results.add(clients.submit(() -> listHoldsOnly(analystA, refA, refB)));
                results.add(clients.submit(() -> listHoldsOnly(analystB, refB, refA)));
            }
            for (Future<Boolean> r : results) {
                assertThat(r.get()).as("a response contained another tenant's break").isTrue();
            }
        }

        assertThat(probe.requests.get()).isGreaterThanOrEqualTo(2 * REQUESTS_PER_TENANT);
        assertThat(probe.platformThreadRequests.get())
            .as("every request must have run on a virtual thread; platform-thread names seen: %s", probe.platformThreadNames)
            .isZero();
    }

    private boolean listHoldsOnly(String bearer, String ownRef, String foreignRef) {
        List<String> refs = given().header("Authorization", bearer)
            .get("/api/v1/breaks?size=200")
            .then().statusCode(200)
            .extract().jsonPath().getList("breakReference", String.class);
        return refs.contains(ownRef) && !refs.contains(foreignRef);
    }

    private String createBreak(String bearer, String prefix) {
        String ref = prefix + "-" + UUID.randomUUID();
        given().header("Authorization", bearer).contentType(ContentType.JSON)
            .body("""
                {"breakReference":"%s","ledgerAmount":"100.00","externalAmount":"90.00","currency":"USD"}
                """.formatted(ref))
            .post("/api/v1/breaks")
            .then().statusCode(201);
        return ref;
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        RequestThreadProbe requestThreadProbe() {
            return new RequestThreadProbe();
        }
    }

    /** Records, for every request this context serves, whether the handling thread was virtual. Test scope only. */
    static class RequestThreadProbe extends OncePerRequestFilter {

        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger platformThreadRequests = new AtomicInteger();
        final Set<String> platformThreadNames = ConcurrentHashMap.newKeySet();

        void reset() {
            requests.set(0);
            platformThreadRequests.set(0);
            platformThreadNames.clear();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            requests.incrementAndGet();
            if (!Thread.currentThread().isVirtual()) {
                platformThreadRequests.incrementAndGet();
                platformThreadNames.add(Thread.currentThread().getName());
            }
            chain.doFilter(request, response);
        }
    }
}
