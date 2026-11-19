// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.knowledge.KnowledgeOutcome;
import com.reconcileai.application.knowledge.KnowledgeResult;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.knowledge.EkaKnowledgeClient;
import com.reconcileai.infrastructure.knowledge.KnowledgeProperties;
import com.reconcileai.infrastructure.knowledge.TenantServiceTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q1.6 and Q1.7 against the <b>real</b> EKA edge and the platform realm, with reconcile-ai's own client code
 * ({@link TenantServiceTokens} + {@link EkaKnowledgeClient}). Tag {@code eka}: excluded from the default build and CI.
 * No model is called, so no cost. Runbook: {@code docs/proof/q1-6-eka-isolation-runbook.md}.
 * <pre>
 * EKA_LIVE_BASE_URL=http://localhost:8080 KNOWLEDGE_TOKEN_URI=http://localhost:8190/realms/platform/protocol/openid-connect/token \
 * KC_SVC_TENANT_A_SECRET=... KC_SVC_TENANT_B_SECRET=... \
 * mvn verify "-Dfailsafe.excludedGroups=openai,ollama,bedrock,gateway,gateway-live" -Dit.test=RealEkaIsolationIT
 * </pre>
 * Tenant ids are the realm's {@code tenant_id} claims ({@code tenant-a}, {@code tenant-b}); the mapping must match
 * exactly, so this also proves the claim check passes with the real realm.
 */
@Tag("eka")
@EnabledIfEnvironmentVariable(named = "EKA_LIVE_BASE_URL", matches = "https?://.+")
class RealEkaIsolationIT {

    private static final String A = "tenant-a";
    private static final String B = "tenant-b";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static TenantServiceTokens tokens;
    private static EkaKnowledgeClient client;
    private static String base;
    private static String marker;
    private static String documentId;

    @BeforeAll
    static void uploadOneDocumentForTenantA() throws Exception {
        base = System.getenv("EKA_LIVE_BASE_URL");
        KnowledgeProperties props = new KnowledgeProperties(true, base, System.getenv("KNOWLEDGE_TOKEN_URI"),
            "aud-eka-api", Duration.ofSeconds(5), Duration.ofSeconds(10), 5, 800, 2400, false, 50, 1000,
            Duration.ofSeconds(30), Map.of(
                A, new KnowledgeProperties.TenantClient("svc-reconcile-tenant-a", "KC_SVC_TENANT_A_SECRET"),
                B, new KnowledgeProperties.TenantClient("svc-reconcile-tenant-b", "KC_SVC_TENANT_B_SECRET")));
        tokens = new TenantServiceTokens(HTTP, JSON, props, System::getenv, Clock.systemUTC());
        client = new EkaKnowledgeClient(HTTP, JSON, props, tokens, new InjectionScanner(), (o, r) -> { });

        marker = "procedure-" + UUID.randomUUID().toString().replace("-", "");
        String boundary = "b" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + marker
            + ".md\"\r\nContent-Type: text/markdown\r\n\r\n# Synthetic timing procedure\n\nMarker " + marker
            + ". Confirm the value date on the external statement before re-running a timing break.\n\r\n--"
            + boundary + "--\r\n";
        HttpResponse<String> upload = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/documents"))
            .header("Authorization", "Bearer " + tokens.token(A))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).as(upload.body()).isIn(200, 202);
        documentId = JSON.readTree(upload.body()).path("id").asText();

        Instant deadline = Instant.now().plusSeconds(60);
        String status = "";
        while (Instant.now().isBefore(deadline) && !"ready".equals(status) && !"failed".equals(status)) {
            Thread.sleep(1000);
            status = JSON.readTree(get("/api/v1/documents/" + documentId, A).body()).path("status").asText();
        }
        assertThat(status).as("ingestion").isEqualTo("ready");
    }

    @AfterAll
    static void deleteTheDocument() throws Exception {
        if (documentId != null && !documentId.isBlank()) {
            HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/documents/" + documentId))
                .header("Authorization", "Bearer " + tokens.token(A)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        }
    }

    @Test
    void tenantAFindsItsProcedureThroughTheReconcileClient() {
        KnowledgeResult r = client.search(A, marker);
        assertThat(r.outcome()).isEqualTo(KnowledgeOutcome.OK);
        assertThat(r.hits()).anySatisfy(h -> assertThat(h.snippet()).contains(marker));
    }

    /** Q1.6 blocking test: exact text of tenant A's document, searched as tenant B, returns nothing. */
    @Test
    void tenantBSeesNothingOfTenantA() throws Exception {
        KnowledgeResult r = client.search(B, marker);
        assertThat(r.outcome()).isEqualTo(KnowledgeOutcome.EMPTY);
        assertThat(get("/api/v1/documents/" + documentId, B).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/documents", B).body()).doesNotContain(documentId);
    }

    /**
     * Q1.7: ten concurrent lookups for one tenant. Records the outcome mix and latency; asserts only that every
     * lookup returned a typed outcome (no exception, no fabricated hit). Not a capacity claim.
     */
    @Test
    void tenConcurrentLookupsAllReturnATypedOutcome() throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<KnowledgeResult>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> {
                    long t0 = System.nanoTime();
                    KnowledgeResult r = client.search(A, marker);
                    lines.add(r.outcome() + "/" + r.reason() + " " + (System.nanoTime() - t0) / 1_000_000 + "ms");
                    return r;
                });
            }
            for (Future<KnowledgeResult> f : pool.invokeAll(calls)) {
                KnowledgeResult r = f.get();
                assertThat(r.outcome()).isIn(KnowledgeOutcome.OK, KnowledgeOutcome.UNAVAILABLE);
                if (r.outcome() == KnowledgeOutcome.UNAVAILABLE) {
                    assertThat(r.hits()).isEmpty();
                }
            }
        }
        System.out.println("Q1.7 concurrent lookups: " + lines);
    }

    private static HttpResponse<String> get(String path, String tenant) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Bearer " + tokens.token(tenant)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
