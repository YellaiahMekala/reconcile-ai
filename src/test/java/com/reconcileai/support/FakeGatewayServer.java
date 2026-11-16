package com.reconcileai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.reconcileai.infrastructure.ai.llm.MockLlmChatModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for ai-gateway that the whole application talks to over HTTP in the gateway integration tests
 * (platform Q2.3 to Q2.6). One JDK {@link HttpServer} per JVM, started lazily and registered into the Spring
 * properties by {@link #register(DynamicPropertyRegistry)}.
 * <p>
 * What it imitates, and nothing more:
 * <ul>
 *   <li>{@code POST /token}: the realm's client-credentials grant; records the form so a test can check the
 *       audience scope.</li>
 *   <li>{@code POST /api/v1/chat}: answers from a <b>script</b> (next refusal or outcome to return) or, when the
 *       script is empty, with a {@code 200 OK} whose output is the same deterministic proposal the
 *       {@code mock-llm} model would give for the evidence in the request. Every reply is validated against
 *       {@code chat-response.v1.json} or {@code error.v1.json} before it is sent, so the fake cannot drift from
 *       the gateway's published contract.</li>
 *   <li>{@code GET /api/v1/invocations/{id}}: the gateway ledger. A row exists for every call that reached the
 *       "provider" (a 200, a 504 UNKNOWN_OUTCOME, a 409 IDEMPOTENCY_UNKNOWN_OUTCOME, a budget or policy refusal
 *       that the real gateway records); an auth or schema rejection has no row. Unknown ids are 404.</li>
 * </ul>
 * Every chat request is kept ({@link #calls()}): idempotency key, bearer, raw body, and the invocation id that was
 * answered. Tests assert "exactly one request under this key" and "the body never carried the raw IBAN" on that list.
 */
public final class FakeGatewayServer {

    /** One scripted answer for the next chat request. {@code invocationId} null means "no ledger row". */
    public record Scripted(int status, String code, UUID invocationId, String retryAfter) {

        public static Scripted refusal(int status, String code) {
            return new Scripted(status, code, UUID.randomUUID(), null);
        }

        public static Scripted rejection(int status, String code) {
            return new Scripted(status, code, null, null);
        }
    }

    /** A chat request as the fake saw it, plus what it answered. */
    public record Call(String idempotencyKey, String authorization, String body, int status, String code,
                       UUID invocationId, Instant at) { }

    /** The gateway ledger row behind {@code GET /api/v1/invocations/{id}}. */
    public record LedgerRow(UUID invocationId, UUID correlationId, String status, int inputTokens, int outputTokens,
                            String executionCertainty, Instant createdAt, String tenantId) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile FakeGatewayServer instance;

    private final HttpServer server;
    private final JsonSchema responseSchema = schema("/contracts/ai-gateway/chat-response.v1.json");
    private final JsonSchema errorSchema = schema("/contracts/ai-gateway/error.v1.json");
    private final ConcurrentLinkedDeque<Scripted> script = new ConcurrentLinkedDeque<>();
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> tokenForms = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, LedgerRow> ledger = new ConcurrentHashMap<>();
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final Map<String, String> clientTenants = new ConcurrentHashMap<>();
    private final Map<String, String> tokenTenants = new ConcurrentHashMap<>();
    private final List<String> ledgerReads = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean ledgerDown;

    private FakeGatewayServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/token", this::token);
        server.createContext("/api/v1/chat", this::chat);
        server.createContext("/api/v1/invocations/", this::invocation);
        server.start();
    }

    public static synchronized FakeGatewayServer get() {
        if (instance == null) {
            instance = new FakeGatewayServer();
        }
        return instance;
    }

    /** Points {@code reconcile.gateway.*} at this fake and switches the route on for the test context. */
    public static void register(DynamicPropertyRegistry registry) {
        FakeGatewayServer fake = get();
        registry.add("reconcile.gateway.enabled", () -> "true");
        registry.add("reconcile.gateway.base-url", fake::baseUrl);
        registry.add("reconcile.gateway.token-uri", () -> fake.baseUrl() + "/token");
        registry.add("reconcile.gateway.client-id", () -> "reconcile-ai");
        registry.add("reconcile.gateway.client-secret", () -> "test-secret");
        registry.add("reconcile.gateway.token-scope", () -> "aud-ai-gateway");
        registry.add("reconcile.gateway.connect-timeout", () -> "2s");
        registry.add("reconcile.gateway.request-timeout", () -> "10s");
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Clears the script and the seen calls. The ledger is kept: ids stay resolvable across tests, as in a real gateway. */
    public void reset() {
        script.clear();
        calls.clear();
        ledgerReads.clear();
        ledgerDown = false;
    }

    /** Tokens for {@code clientId} carry {@code tenant_id=tenantId}; ledger rows they create are visible only to it. */
    public FakeGatewayServer mapClient(String clientId, String tenantId) {
        clientTenants.put(clientId, tenantId);
        return this;
    }

    /** While true, {@code GET /api/v1/invocations/{id}} answers 500. Cleared by {@link #reset()}. */
    public FakeGatewayServer ledgerDown(boolean down) {
        this.ledgerDown = down;
        return this;
    }

    /** Drops a ledger row, so its id answers 404 (a gateway that lost or never kept the record). */
    public void forget(UUID invocationId) {
        ledger.remove(invocationId);
    }

    /** Invocation ids read from the ledger since the last reset, in order. */
    public List<String> ledgerReads() {
        return List.copyOf(ledgerReads);
    }

    /** The tenant claim of the token a chat call carried, or null for the shared client's opaque token. */
    public String tenantOfCall(Call call) {
        return tenantOf(call.authorization());
    }

    public FakeGatewayServer enqueue(Scripted next) {
        script.addLast(next);
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> callsForKey(String idempotencyKey) {
        return calls().stream().filter(c -> idempotencyKey.equals(c.idempotencyKey())).toList();
    }

    public List<String> tokenForms() {
        return List.copyOf(tokenForms);
    }

    public LedgerRow ledger(UUID invocationId) {
        return ledger.get(invocationId);
    }

    // ------------------------------------------------------------------ handlers

    /**
     * An opaque {@code svc-token-N} for the shared client (as before). A client mapped with {@link #mapClient} gets a
     * JWT-shaped token (unsigned) whose {@code tenant_id} claim is the mapped tenant, as the realm's per-tenant
     * clients do; the gateway bills and scopes its ledger by that claim.
     */
    private void token(HttpExchange ex) throws IOException {
        String form = read(ex);
        tokenForms.add(form);
        int n = tokensIssued.incrementAndGet();
        String clientId = formValue(form, "client_id");
        String tenant = clientId == null ? null : clientTenants.get(clientId);
        String token;
        if (tenant == null) {
            token = "svc-token-" + n;
        } else {
            java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64.encodeToString(JSON.writeValueAsBytes(Map.of(
                "tenant_id", tenant, "azp", clientId, "jti", "svc-token-" + n)));
            token = header + "." + payload + ".unsigned";
            tokenTenants.put(token, tenant);
        }
        send(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300}", null);
    }

    private static String formValue(String form, String name) {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** The tenant a bearer token was issued for, or null for the shared client's opaque token. */
    private String tenantOf(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return tokenTenants.get(authorization.substring("Bearer ".length()));
    }

    private void chat(HttpExchange ex) throws IOException {
        String body = read(ex);
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Scripted next = script.pollFirst();
        UUID correlationId = UUID.randomUUID();
        if (next == null) {
            UUID invocationId = UUID.randomUUID();
            String output = MockLlmChatModel.answer(MockLlmChatModel.evidenceBlocks(userContent(body)));
            int in = Math.max(1, body.length() / 4);
            int out = Math.max(1, output.length() / 4);
            ledger.put(invocationId, new LedgerRow(invocationId, correlationId, "OK", in, out, "EXECUTED", Instant.now(),
                tenantOf(auth)));
            calls.add(new Call(key, auth, body, 200, null, invocationId, Instant.now()));
            Map<String, Object> reply = Map.of(
                "invocationId", invocationId.toString(), "correlationId", correlationId.toString(), "status", "OK",
                "output", output,
                "usage", Map.of("inputTokens", in, "outputTokens", out, "source", "ESTIMATED"));
            send(ex, 200, JSON.writeValueAsString(reply), null);
            return;
        }
        if (next.invocationId() != null) {
            String certainty = "UNKNOWN_OUTCOME".equals(next.code()) || "IDEMPOTENCY_UNKNOWN_OUTCOME".equals(next.code())
                ? "UNKNOWN" : "NOT_EXECUTED";
            ledger.put(next.invocationId(), new LedgerRow(next.invocationId(), correlationId, next.code(), 0, 0,
                certainty, Instant.now(), tenantOf(auth)));
        }
        calls.add(new Call(key, auth, body, next.status(), next.code(), next.invocationId(), Instant.now()));
        StringBuilder error = new StringBuilder("{\"code\":\"").append(next.code()).append("\",\"message\":\"scripted\",")
            .append("\"correlationId\":\"").append(correlationId).append("\",");
        if (next.invocationId() != null) {
            error.append("\"invocationId\":\"").append(next.invocationId()).append("\",");
        }
        error.append("\"timestamp\":\"").append(Instant.now()).append("\"}");
        send(ex, next.status(), error.toString(), next.retryAfter());
    }

    /** Mirrors the gateway's InvocationView; the real endpoint also hides other tenants' rows (404). */
    private void invocation(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        ledgerReads.add(id);
        if (ledgerDown) {
            send(ex, 500, "{\"code\":\"INTERNAL\",\"message\":\"ledger down\",\"timestamp\":\"" + Instant.now() + "\"}", null);
            return;
        }
        LedgerRow row;
        try {
            row = ledger.get(UUID.fromString(id));
        } catch (IllegalArgumentException notAUuid) {
            row = null;
        }
        String reader = tenantOf(ex.getRequestHeaders().getFirst("Authorization"));
        if (row != null && row.tenantId() != null && !row.tenantId().equals(reader)) {
            row = null; // another tenant's invocation: the real gateway answers 404, never 403
        }
        if (row == null) {
            send(ex, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no such invocation\",\"timestamp\":\"" + Instant.now() + "\"}", null);
            return;
        }
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("invocationId", row.invocationId().toString());
        view.put("correlationId", row.correlationId().toString());
        view.put("taskClass", "REASONING");
        view.put("status", row.status());
        view.put("modelKey", "fake/model");
        view.put("provider", "FAKE");
        view.put("inputTokens", row.inputTokens());
        view.put("outputTokens", row.outputTokens());
        view.put("usageSource", "ESTIMATED");
        view.put("actualCostUsd", 0);
        view.put("executionCertainty", row.executionCertainty());
        view.put("gatewayLatencyMs", 1);
        view.put("createdAt", row.createdAt().toString());
        send(ex, 200, JSON.writeValueAsString(view), null);
    }

    // ------------------------------------------------------------------ helpers

    private static String userContent(String body) {
        try {
            JsonNode messages = JSON.readTree(body).path("messages");
            StringBuilder sb = new StringBuilder();
            for (JsonNode m : messages) {
                sb.append(m.path("content").asText("")).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String read(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void send(HttpExchange ex, int status, String body, String retryAfter) throws IOException {
        if (ex.getRequestURI().getPath().endsWith("/chat")) {
            JsonSchema expected = status == 200 ? responseSchema : errorSchema;
            Set<ValidationMessage> violations = expected.validate(JSON.readTree(body));
            if (!violations.isEmpty()) {
                throw new IllegalStateException("fake gateway reply violates the contract: " + violations + " " + body);
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) {
            ex.getResponseHeaders().add("Retry-After", retryAfter);
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static JsonSchema schema(String path) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(FakeGatewayServer.class.getResourceAsStream(path));
    }
}
