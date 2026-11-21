// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the EKA edge and the platform realm token endpoint (platform Q1.2 contract tests and Q1 ITs).
 * <ul>
 *   <li>{@code POST /token}: client credentials; issues an unsigned JWT whose {@code tenant_id} is the tenant mapped
 *       to the client with {@link #mapClient}. Unmapped clients get 401.</li>
 *   <li>{@code POST /api/v1/search}: the request body is validated against {@code contracts/eka/search-request.json}
 *       (copied from EKA's openapi.json); an invalid body is a 422. Answers from a script, else from the documents
 *       {@link #index indexed} for the <b>token's</b> tenant only, imitating EKA's owner isolation. Every 200 body is
 *       validated against {@code search-response.json} before it is sent.</li>
 *   <li>Any other path is 404 and recorded, so a test can prove nothing else (chat, conversations) was called.</li>
 * </ul>
 */
public final class FakeEkaServer {

    /** One recorded request. {@code tenant} is the token's claim (null without a bearer). */
    public record Call(String path, String tenant, String body) { }

    /** Next answer for a search: an HTTP status (200 uses {@code results}), optionally after a delay. */
    public record Scripted(int status, List<Doc> results, long delayMs, String retryAfter) {
        public Scripted(int status, List<Doc> results, long delayMs) {
            this(status, results, delayMs, null);
        }

        public static Scripted status(int status) {
            return new Scripted(status, List.of(), 0);
        }

        /** A 429 carrying {@code Retry-After: <seconds>} like EKA's edge limiter. */
        public static Scripted rateLimited(String retryAfterSeconds) {
            return new Scripted(429, List.of(), 0, retryAfterSeconds);
        }

        public static Scripted slow(long delayMs) {
            return new Scripted(200, List.of(), delayMs);
        }

        public static Scripted hits(Doc... docs) {
            return new Scripted(200, List.of(docs), 0);
        }
    }

    public record Doc(UUID chunkId, UUID documentId, String filename, String text) {
        public static Doc of(String filename, String text) {
            return new Doc(UUID.randomUUID(), UUID.randomUUID(), filename, text);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile FakeEkaServer shared;

    private final HttpServer server;
    private final Schema requestSchema = schema("/contracts/eka/search-request.json");
    private final Schema responseSchema = schema("/contracts/eka/search-response.json");
    private final Map<String, String> clientTenants = new ConcurrentHashMap<>();
    private final Map<String, List<Doc>> docs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Scripted> script = new ConcurrentLinkedDeque<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<String> tokenForms = Collections.synchronizedList(new ArrayList<>());

    private FakeEkaServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/token", this::token);
        server.createContext("/", this::api);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** A private instance (unit tests); stop it when done. */
    public static FakeEkaServer start() {
        return new FakeEkaServer();
    }

    /** The JVM-wide instance for Spring ITs (the application context is cached across classes). */
    public static synchronized FakeEkaServer get() {
        if (shared == null) {
            shared = new FakeEkaServer();
        }
        return shared;
    }

    public void stop() {
        server.stop(0);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        script.clear();
        calls.clear();
        tokenForms.clear();
        docs.clear();
    }

    public void mapClient(String clientId, String tenant) {
        clientTenants.put(clientId, tenant);
    }

    public void index(String tenant, Doc doc) {
        docs.computeIfAbsent(tenant, t -> new CopyOnWriteArrayList<>()).add(doc);
    }

    public void enqueue(Scripted s) {
        script.add(s);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<String> tokenForms() {
        return List.copyOf(tokenForms);
    }

    /** An unsigned JWT-shaped token with the given tenant claim (for unit tests that skip the token endpoint). */
    public static String jwtFor(String tenant) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + enc.encodeToString(("{\"tenant_id\":\"" + tenant + "\",\"aud\":\"eka-api\"}").getBytes(StandardCharsets.UTF_8))
            + ".x";
    }

    private void token(HttpExchange ex) throws IOException {
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        tokenForms.add(form);
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
        send(ex, 200, "{\"access_token\":\"" + jwtFor(tenant) + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
    }

    private void api(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        String tenant = tenantOf(ex.getRequestHeaders().getFirst("Authorization"));
        calls.add(new Call(path, tenant, body));
        if (!"POST".equals(ex.getRequestMethod()) || !"/api/v1/search".equals(path)) {
            send(ex, 404, problem(404));
            return;
        }
        if (tenant == null) {
            send(ex, 401, problem(401));
            return;
        }
        JsonNode request = JSON.readTree(body);
        java.util.List<Error> errors = requestSchema.validate(request);
        if (!errors.isEmpty()) {
            send(ex, 422, "{\"detail\":[{\"msg\":\"invalid\"}]}");
            return;
        }
        Scripted s = script.poll();
        if (s != null && s.delayMs() > 0) {
            try {
                Thread.sleep(s.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (s != null && s.status() != 200) {
            if (s.retryAfter() != null) {
                ex.getResponseHeaders().add("Retry-After", s.retryAfter());
            }
            send(ex, s.status(), problem(s.status()));
            return;
        }
        List<Doc> results = s != null && !s.results().isEmpty() ? s.results() : docs.getOrDefault(tenant, List.of());
        ObjectNode response = JSON.createObjectNode().put("query", request.path("query").asText());
        ArrayNode arr = response.putArray("results");
        int rank = 1;
        for (Doc d : results.subList(0, Math.min(results.size(), request.path("top_k").asInt(5)))) {
            ObjectNode r = arr.addObject();
            r.put("rank", rank++).put("chunk_id", d.chunkId().toString()).put("document_id", d.documentId().toString())
                .put("filename", d.filename()).put("text", d.text());
            r.putObject("locator").put("page", 1);
            r.putObject("scores").put("fused", 0.5).put("cosine", 0.8).putNull("rerank");
        }
        java.util.List<Error> responseErrors = responseSchema.validate(response);
        if (!responseErrors.isEmpty()) {
            throw new IllegalStateException("fake EKA reply breaks the contract: " + responseErrors);
        }
        send(ex, 200, JSON.writeValueAsString(response));
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
            return JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path("tenant_id").asText(null);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String problem(int status) {
        return "{\"type\":\"about:blank\",\"title\":\"error\",\"status\":" + status
            + ",\"detail\":\"scripted\",\"instance\":\"/api/v1/search\",\"request_id\":\"" + UUID.randomUUID() + "\"}";
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Schema schema(String resource) {
        try (InputStream in = FakeEkaServer.class.getResourceAsStream(resource)) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(JSON.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
