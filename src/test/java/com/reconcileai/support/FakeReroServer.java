// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for RERO and the platform realm token endpoint (platform Q3.5 ITs). It behaves like RERO v0 in miniature:
 * <ul>
 *   <li>{@code POST /token}: client credentials; an unsigned JWT with the mapped {@code tenant_id} and the audiences
 *       {@code rero-api} and {@code ai-gateway} (or only one of them, see {@link #singleAudience}).</li>
 *   <li>{@code POST /v1/recommendations}: checks {@code Idempotency-Key == jobId}; same key returns the same run (200)
 *       without a second run. A new run executes the {@link Scenario} on a virtual thread: it calls reconcile-ai's
 *       internal API with the run token it was given (break, then evidence), then finishes as scripted.</li>
 *   <li>{@code GET /v1/recommendations/{runId}}: the run, or 404 for another tenant's run.</li>
 * </ul>
 * Nothing is persisted; the run token is held only inside the scenario thread.
 */
public final class FakeReroServer {

    /** How the next run ends. */
    public enum Scenario { PASS, CHALLENGE, INVALID_CITATION, KB_CITATION, FAILED_MODEL_UNAVAILABLE, HANG, DOWN, NO_EVIDENCE_CALL }

    /** One received start request (the run token itself is not kept). */
    public record Start(String tenant, String idempotencyKey, JsonNode body, boolean hadRunToken) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile FakeReroServer shared;

    private final HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> clientTenants = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> runs = new ConcurrentHashMap<>();       // runId -> run
    private final Map<String, String> runTenants = new ConcurrentHashMap<>();
    private final Map<String, String> runsByKey = new ConcurrentHashMap<>();     // tenant|key -> runId
    private final List<Start> starts = new CopyOnWriteArrayList<>();
    private final List<Integer> internalStatuses = new CopyOnWriteArrayList<>();
    private final AtomicInteger runsCreated = new AtomicInteger();
    private final java.util.Set<String> offContract = ConcurrentHashMap.newKeySet();
    private volatile Scenario scenario = Scenario.PASS;
    private volatile String internalBaseUrl;
    private volatile boolean singleAudience;

    private FakeReroServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/token", this::token);
        server.createContext("/", this::api);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static synchronized FakeReroServer get() {
        if (shared == null) {
            shared = new FakeReroServer();
        }
        return shared;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        runs.clear();
        runTenants.clear();
        runsByKey.clear();
        starts.clear();
        internalStatuses.clear();
        runsCreated.set(0);
        offContract.clear();
        scenario = Scenario.PASS;
        singleAudience = false;
    }

    public void mapClient(String clientId, String tenant) {
        clientTenants.put(clientId, tenant);
    }

    public void scenario(Scenario s) {
        this.scenario = s;
    }

    public void internalBaseUrl(String url) {
        this.internalBaseUrl = url;
    }

    public void singleAudience(boolean on) {
        this.singleAudience = on;
    }

    public List<Start> starts() {
        return List.copyOf(starts);
    }

    public int runsCreated() {
        return runsCreated.get();
    }

    /** HTTP statuses the fake got from reconcile-ai's internal API, in order. */
    public List<Integer> internalStatuses() {
        return List.copyOf(internalStatuses);
    }

    // ------------------------------------------------------------------ token endpoint

    private void token(HttpExchange ex) throws IOException {
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String clientId = null;
        for (String pair : form.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals("client_id")) {
                clientId = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        String tenant = clientId == null ? null : clientTenants.get(clientId);
        if (tenant == null) {
            send(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        String aud = singleAudience ? "\"rero-api\"" : "[\"rero-api\",\"ai-gateway\"]";
        send(ex, 200, "{\"access_token\":\"" + jwt("{\"tenant_id\":\"" + tenant + "\",\"aud\":" + aud
            + ",\"roles\":[\"RERO_CALLER\",\"GATEWAY_CONSUMER\"]}") + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
    }

    // ------------------------------------------------------------------ RERO API

    private void api(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String tenant = tenantOf(ex.getRequestHeaders().getFirst("Authorization"));
        if (tenant == null) {
            send(ex, 401, error("UNAUTHENTICATED"));
            return;
        }
        if ("POST".equals(ex.getRequestMethod()) && "/v1/recommendations".equals(path)) {
            start(ex, tenant);
            return;
        }
        if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/v1/recommendations/")) {
            String runId = path.substring("/v1/recommendations/".length());
            ObjectNode run = runs.get(runId);
            if (run == null || !tenant.equals(runTenants.get(runId))) {
                send(ex, 404, error("NOT_FOUND"));
                return;
            }
            synchronized (run) {
                sendRun(ex, 200, run);
            }
            return;
        }
        send(ex, 404, error("NOT_FOUND"));
    }

    private void start(HttpExchange ex, String tenant) throws IOException {
        if (scenario == Scenario.DOWN) {
            send(ex, 503, error("UNAVAILABLE"));
            return;
        }
        JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        String jobId = body.path("jobId").asText();
        String runToken = body.path("runToken").asText(null);
        starts.add(new Start(tenant, key, body, runToken != null && !runToken.isBlank()));
        if (key == null || !key.equals(jobId) || !ReroContracts.validate("start-request.v1", body).isEmpty()) {
            send(ex, 400, error("INVALID_REQUEST"));
            return;
        }
        String existing = runsByKey.get(tenant + "|" + key);
        if (existing != null) {
            ObjectNode run = runs.get(existing);
            synchronized (run) {
                sendRun(ex, 200, run);
            }
            return;
        }
        String runId = UUID.randomUUID().toString();
        ObjectNode run = JSON.createObjectNode();
        run.put("runId", runId).put("jobId", jobId).put("status", "RUNNING").put("stage", "LOAD_CONTEXT")
            .putNull("failureCode").put("toolCallsUsed", 0).put("modelCallsUsed", 0);
        run.putArray("gatewayInvocationIds");
        run.put("startedAt", Instant.now().toString()).putNull("finishedAt").putNull("result");
        runs.put(runId, run);
        runTenants.put(runId, tenant);
        runsByKey.put(tenant + "|" + key, runId);
        runsCreated.incrementAndGet();
        Scenario s = scenario;
        if (s == Scenario.KB_CITATION) {
            offContract.add(runId);
        }
        String note = body.path("analystNote").asText("");
        Thread.ofVirtual().start(() -> execute(run, jobId, runToken, s, note));
        synchronized (run) {
            sendRun(ex, 202, run);
        }
    }

    private void execute(ObjectNode run, String jobId, String runToken, Scenario s, String note) {
        try {
            stage(run, "RETRIEVE");
            List<String> served = new ArrayList<>();
            if (s != Scenario.NO_EVIDENCE_CALL) {
                internal(run, jobId, runToken, "/break");
                JsonNode evidence = internal(run, jobId, runToken, "/evidence?query="
                    + URLEncoder.encode("fee timing settlement " + note, StandardCharsets.UTF_8));
                evidence.path("chunks").forEach(c -> served.add(c.path("chunkId").asText()));
            }
            stage(run, "DRAFT");
            if (s == Scenario.HANG) {
                return; // stays RUNNING
            }
            synchronized (run) {
                run.put("modelCallsUsed", 2);
                ((ArrayNode) run.get("gatewayInvocationIds")).add(UUID.randomUUID().toString())
                    .add(UUID.randomUUID().toString());
                run.put("stage", "DONE").put("finishedAt", Instant.now().toString());
                if (s == Scenario.FAILED_MODEL_UNAVAILABLE) {
                    run.put("status", "FAILED").put("failureCode", "MODEL_UNAVAILABLE");
                    return;
                }
                List<String> cited = switch (s) {
                    case INVALID_CITATION -> List.of(UUID.randomUUID().toString());
                    case KB_CITATION -> List.of("kb:" + UUID.randomUUID());
                    default -> served.isEmpty() ? List.of() : List.of(served.get(0));
                };
                ObjectNode result = run.putObject("result");
                result.put("rootCauseCategory", "FEE_OR_CHARGE")
                    .put("explanation", "The variance matches a bank fee shown in the cited evidence.");
                ArrayNode c = result.putArray("citedChunkIds");
                cited.forEach(c::add);
                result.put("confidence", 0.9)
                    .put("selfCheck", s == Scenario.CHALLENGE ? "CHALLENGE" : "PASS")
                    .put("selfCheckReason", s == Scenario.CHALLENGE ? "A cited claim is not supported" : "Consistent");
                run.put("status", "SUCCEEDED");
            }
        } catch (RuntimeException e) {
            synchronized (run) {
                run.put("status", "FAILED").put("failureCode", "TOOL_UNAVAILABLE").put("stage", "DONE")
                    .put("finishedAt", Instant.now().toString()).putNull("result");
            }
        }
    }

    private JsonNode internal(ObjectNode run, String jobId, String runToken, String tool) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                    URI.create(internalBaseUrl + "/internal/rero/v1/jobs/" + jobId + tool))
                .header("Authorization", "Bearer " + runToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            internalStatuses.add(res.statusCode());
            synchronized (run) {
                run.put("toolCallsUsed", run.path("toolCallsUsed").asInt() + 1);
            }
            if (res.statusCode() != 200) {
                throw new IllegalStateException("internal tool " + res.statusCode());
            }
            return JSON.readTree(res.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void stage(ObjectNode run, String stage) {
        synchronized (run) {
            run.put("stage", stage);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String jwt(String claims) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + enc.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".x";
    }

    private static String tenantOf(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String[] parts = authorization.substring(7).split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode aud = claims.path("aud");
            boolean ok = aud.isArray() && contains(aud, "rero-api") && contains(aud, "ai-gateway");
            return ok ? claims.path("tenant_id").asText(null) : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean contains(JsonNode arr, String v) {
        for (JsonNode n : arr) {
            if (v.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"scripted\"}";
    }

    /** Every Run this fake returns must itself satisfy run.v1.json, so the ITs exercise contract-shaped bodies only. */
    private void sendRun(HttpExchange ex, int status, ObjectNode run) throws IOException {
        var errors = ReroContracts.validate("run.v1", run);
        // KB_CITATION deliberately breaks the schema (kb: is not a uuid) to prove reconcile-ai re-checks citations.
        if (!errors.isEmpty() && !offContract.contains(run.path("runId").asText())) {
            throw new IllegalStateException("fake RERO run breaks the contract: " + errors);
        }
        send(ex, status, JSON.writeValueAsString(run));
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
