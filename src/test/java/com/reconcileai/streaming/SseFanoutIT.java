// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.streaming;

import com.reconcileai.ReconcileAiApplication;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.InvestigationTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two real application instances (same Postgres, same Redis). The job runs on NODE 1; the browser's stream is
 * connected to NODE 2. Without cross-node fan-out NODE 2 would only ever send the initial snapshot.
 */
class SseFanoutIT extends AbstractPostgresIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    static ConfigurableApplicationContext node2;

    @DynamicPropertySource
    static void fanout(DynamicPropertyRegistry r) {
        r.add("reconcile.streaming.fanout", () -> "redis");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    final String analyst = JwtTestSupport.bearer("fan-a", "TENANT_FANOUT", List.of("RECON_ANALYST"));

    static synchronized int node2Port() {
        if (node2 == null) {
            Map<String, Object> p = new HashMap<>();
            p.put("server.port", "0");
            p.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            p.put("spring.datasource.username", "reconcile_app");
            p.put("spring.datasource.password", "reconcile_app");
            p.put("spring.flyway.url", POSTGRES.getJdbcUrl());
            p.put("spring.flyway.user", POSTGRES.getUsername());
            p.put("spring.flyway.password", POSTGRES.getPassword());
            p.put("spring.security.oauth2.resourceserver.jwt.public-key-location", "classpath:jwt/dev-public.pem");
            p.put("reconcile.streaming.fanout", "redis");
            p.put("spring.data.redis.host", REDIS.getHost());
            p.put("spring.data.redis.port", REDIS.getMappedPort(6379));
            // Command-line args, not defaultProperties: they must win over any machine-level env vars (e.g. DB_URL).
            String[] args = p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
            node2 = new SpringApplicationBuilder(ReconcileAiApplication.class).profiles("test", "mock-llm").run(args);
        }
        return Integer.parseInt(node2.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stopNode2() {
        if (node2 != null) {
            node2.close();
        }
    }

    @Test
    void streamOnAnotherNodeReceivesEveryStageOfAJobRunningOnThisNode() throws Exception {
        int otherNode = node2Port();
        RestAssured.port = port; // NODE 1 receives the API calls, so its executor runs the job
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");
        ApiTestSupport.upload(analyst, breakId, "/fixtures/evidence/timing-slow-provider.txt", "text/plain");
        String jobId = InvestigationTestSupport.start(analyst, breakId, null); // model call takes ~3 s

        HttpResponse<String> res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + otherNode + "/api/v1/investigations/" + jobId + "/events"))
                .timeout(Duration.ofSeconds(30)).header("Accept", "text/event-stream")
                .header("Authorization", analyst).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        String body = res.body();
        // Snapshot was taken while NODE 1 was still in the model call; these stages can only have come via Redis.
        assertThat(body).contains("\"stage\":\"VERIFYING\"").contains("\"stage\":\"COMPLETED\"").contains("\"terminal\":true");
        assertThat(body.split("event:progress", -1).length - 1).isGreaterThanOrEqualTo(3);
    }
}
