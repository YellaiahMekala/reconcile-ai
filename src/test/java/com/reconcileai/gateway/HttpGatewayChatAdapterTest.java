package com.reconcileai.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.reconcileai.application.gateway.GatewayCallException;
import com.reconcileai.application.gateway.GatewayCallLog.Attempt;
import com.reconcileai.application.gateway.GatewayCallLog.Outcome;
import com.reconcileai.application.gateway.GatewayChatPort;
import com.reconcileai.application.gateway.GatewayChatPort.Reply;
import com.reconcileai.application.gateway.GatewayChatPort.Request;
import com.reconcileai.application.gateway.IdempotencyKeys;
import com.reconcileai.infrastructure.gateway.ClientCredentialsTokenSource;
import com.reconcileai.infrastructure.gateway.GatewayProperties;
import com.reconcileai.infrastructure.gateway.HttpGatewayChatAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP adapter against a fake ai-gateway (JDK HttpServer): request shape per {@code chat-request.v1.json},
 * the service token (with the audience scope), the idempotency key format, the retry rule from
 * CONSUMER_CONTRACT.md §4, and one call-log row per attempt (platform Q2.4).
 * <p>
 * Every scripted reply is itself checked against {@code chat-response.v1.json} or {@code error.v1.json}, so the fake
 * cannot drift from the contract the real gateway publishes.
 */
class HttpGatewayChatAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final String TENANT = "TENANT_ADAPTER";
    private static final UUID JOB_ID = UUID.fromString("0f4b1c2e-6a7d-4f2a-9b3c-1d2e3f4a5b6c");
    private static final String INVOCATION_1 = "11111111-1111-4111-8111-111111111111";
    private static final String INVOCATION_2 = "22222222-2222-4222-8222-222222222222";
    private static final String CORRELATION = "c0ffee00-0000-4000-8000-000000000001";

    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchema requestSchema = schema("/contracts/ai-gateway/chat-request.v1.json");
    private final JsonSchema responseSchema = schema("/contracts/ai-gateway/chat-response.v1.json");
    private final JsonSchema errorSchema = schema("/contracts/ai-gateway/error.v1.json");

    private HttpServer server;
    private GatewayProperties properties;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final List<String> tokenForms = new ArrayList<>();
    private final Deque<Scripted> chatReplies = new ArrayDeque<>();
    private final List<Seen> seen = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<Attempt> logged = new ArrayList<>();

    private record Scripted(int status, String body, String retryAfter) { }

    private record Seen(String authorization, String idempotencyKey, String body) { }

    private JsonSchema schema(String path) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(getClass().getResourceAsStream(path));
    }

    @BeforeEach
    void startFakeGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            tokenCalls.incrementAndGet();
            String form = read(ex.getRequestBody());
            tokenForms.add(form);
            respond(ex, 200, "{\"access_token\":\"svc-token-1\",\"token_type\":\"Bearer\",\"expires_in\":300}", null);
        });
        server.createContext("/api/v1/chat", ex -> {
            seen.add(new Seen(ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("Idempotency-Key"), read(ex.getRequestBody())));
            Scripted next = chatReplies.poll();
            if (next == null) {
                respond(ex, 500, error("TEST_SCRIPT_EXHAUSTED", null), null);
                return;
            }
            respond(ex, next.status(), next.body(), next.retryAfter());
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new GatewayProperties(true, base, base + "/token", "reconcile-ai", "secret", "aud-ai-gateway",
            Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GatewayChatPort adapter(Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        ClientCredentialsTokenSource tokens = new ClientCredentialsTokenSource(http, json, properties, clock);
        return new HttpGatewayChatAdapter(http, json, properties, tokens, clock, sleeps::add, logged::add);
    }

    private static Request request(Instant deadline) {
        return new Request(TENANT, JOB_ID, IdempotencyKeys.forStep(JOB_ID, "investigate", 1),
            "You are a reconciliation analyst.", "Investigate break B-1.", 700, 0.0, deadline);
    }

    /** A 200 body exactly as gateway-api's ChatResponse serialises it (null fields omitted). */
    private static String ok(String invocationId) {
        return "{\"invocationId\":\"" + invocationId + "\",\"correlationId\":\"" + CORRELATION + "\",\"status\":\"OK\","
            + "\"output\":\"{\\\"ok\\\":true}\",\"usage\":{\"inputTokens\":12,\"outputTokens\":3,\"source\":\"PROVIDER_REPORTED\"},"
            + "\"risk\":\"ANNOTATE\"}";
    }

    /** A non-2xx body as gateway-api's ApiError serialises it; invocationId only when a ledger row exists. */
    private static String error(String code, String invocationId) {
        return "{\"code\":\"" + code + "\",\"message\":\"m\",\"correlationId\":\"" + CORRELATION + "\","
            + (invocationId == null ? "" : "\"invocationId\":\"" + invocationId + "\",")
            + "\"timestamp\":\"2026-11-15T10:00:00Z\"}";
    }

    @Test
    void requestMatchesTheContractSchemaAndCarriesTokenAndKey() throws Exception {
        chatReplies.add(new Scripted(200, ok(INVOCATION_1), null));

        Reply reply = adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(120)));

        assertThat(seen).hasSize(1);
        Seen call = seen.get(0);
        JsonNode body = json.readTree(call.body());
        Set<ValidationMessage> violations = requestSchema.validate(body);
        assertThat(violations).as("chat-request.v1.json violations").isEmpty();
        assertThat(body.path("taskClass").asText()).isEqualTo("REASONING");
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("SYSTEM");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("USER");
        assertThat(body.path("settings").path("maxOutputTokens").asInt()).isEqualTo(700);
        assertThat(body.path("settings").path("temperature").asDouble()).isEqualTo(0.0);

        assertThat(call.authorization()).isEqualTo("Bearer svc-token-1");
        assertThat(call.idempotencyKey()).isEqualTo("recon:" + JOB_ID + ":investigate:1");
        assertThat(IdempotencyKeys.GATEWAY_RULE.matcher(call.idempotencyKey()).matches()).isTrue();

        assertThat(reply.invocationId()).isEqualTo(UUID.fromString(INVOCATION_1));
        assertThat(reply.status()).isEqualTo("OK");
        assertThat(reply.output()).isEqualTo("{\"ok\":true}");
        assertThat(reply.inputTokens()).isEqualTo(12);
        assertThat(reply.outputTokens()).isEqualTo(3);

        assertThat(logged).hasSize(1);
        Attempt a = logged.get(0);
        assertThat(a.tenantId()).isEqualTo(TENANT);
        assertThat(a.jobId()).isEqualTo(JOB_ID);
        assertThat(a.idempotencyKey()).isEqualTo(call.idempotencyKey());
        assertThat(a.attempt()).isEqualTo(1);
        assertThat(a.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(a.httpStatus()).isEqualTo(200);
        assertThat(a.invocationId()).isEqualTo(UUID.fromString(INVOCATION_1));
        assertThat(a.gatewayStatus()).isEqualTo("OK");
        assertThat(a.inputTokens()).isEqualTo(12);
        assertThat(a.outputTokens()).isEqualTo(3);
    }

    @Test
    void serviceTokenIsFetchedOnceWithTheAudienceScopeAndCachedAcrossCalls() {
        chatReplies.add(new Scripted(200, ok(INVOCATION_1), null));
        chatReplies.add(new Scripted(200, ok(INVOCATION_2), null));
        GatewayChatPort port = adapter(Clock.fixed(NOW, ZoneOffset.UTC));

        port.chat(request(NOW.plusSeconds(120)));
        port.chat(request(NOW.plusSeconds(120)));

        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(tokenForms.get(0)).contains("grant_type=client_credentials").contains("client_id=reconcile-ai")
            .contains("scope=aud-ai-gateway");
        assertThat(seen).extracting(Seen::authorization).containsExactly("Bearer svc-token-1", "Bearer svc-token-1");
    }

    @Test
    void retriesOnceOnNotSentWithTheSameKeyAndBody() {
        chatReplies.add(new Scripted(503, error("NOT_SENT", null), "1"));
        chatReplies.add(new Scripted(200, ok(INVOCATION_1), null));

        Reply reply = adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(120)));

        assertThat(reply.status()).isEqualTo("OK");
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).idempotencyKey()).isEqualTo(seen.get(0).idempotencyKey());
        assertThat(seen.get(1).body()).isEqualTo(seen.get(0).body());
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));

        // Both attempts are on our side of the ledger, under one key.
        assertThat(logged).extracting(Attempt::attempt).containsExactly(1, 2);
        assertThat(logged).extracting(Attempt::idempotencyKey).containsOnly(seen.get(0).idempotencyKey());
        assertThat(logged).extracting(Attempt::outcome).containsExactly(Outcome.REFUSED, Outcome.SUCCESS);
        assertThat(logged.get(0).code()).isEqualTo("NOT_SENT");
        assertThat(logged.get(0).invocationId()).isNull();
        assertThat(logged.get(1).invocationId()).isEqualTo(UUID.fromString(INVOCATION_1));
    }

    @Test
    void neverRetriesAnUnknownOutcome() {
        chatReplies.add(new Scripted(504, error("UNKNOWN_OUTCOME", INVOCATION_1), null));
        chatReplies.add(new Scripted(200, ok(INVOCATION_2), null));

        assertThatThrownBy(() -> adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(120))))
            .isInstanceOf(GatewayCallException.class)
            .satisfies(e -> {
                assertThat(((GatewayCallException) e).httpStatus()).isEqualTo(504);
                assertThat(((GatewayCallException) e).code()).isEqualTo("UNKNOWN_OUTCOME");
                assertThat(((GatewayCallException) e).invocationId()).isEqualTo(UUID.fromString(INVOCATION_1));
            });
        assertThat(seen).hasSize(1);
        assertThat(sleeps).isEmpty();

        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(logged.get(0).httpStatus()).isEqualTo(504);
        assertThat(logged.get(0).invocationId()).isEqualTo(UUID.fromString(INVOCATION_1));
    }

    @Test
    void neverRetriesIdempotencyUnknownOutcomeOrBudgetBlocked() {
        for (String code : List.of("IDEMPOTENCY_UNKNOWN_OUTCOME", "BUDGET_BLOCKED")) {
            seen.clear();
            chatReplies.clear();
            logged.clear();
            chatReplies.add(new Scripted(code.startsWith("IDEMP") ? 409 : 429, error(code, INVOCATION_1), "1"));
            chatReplies.add(new Scripted(200, ok(INVOCATION_2), null));

            assertThatThrownBy(() -> adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(120))))
                .isInstanceOf(GatewayCallException.class)
                .satisfies(e -> assertThat(((GatewayCallException) e).code()).isEqualTo(code));
            assertThat(seen).as(code).hasSize(1);
            assertThat(logged).as(code).hasSize(1);
            assertThat(logged.get(0).outcome()).as(code)
                .isEqualTo(code.startsWith("IDEMP") ? Outcome.UNKNOWN : Outcome.REFUSED);
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void doesNotRetryWhenTheRetryCannotFinishBeforeTheDeadline() {
        chatReplies.add(new Scripted(503, error("OVERLOADED", null), "5"));
        chatReplies.add(new Scripted(200, ok(INVOCATION_1), null));

        // Retry-After 5 s + request timeout 10 s would end after the 8 s deadline.
        assertThatThrownBy(() -> adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(8))))
            .isInstanceOf(GatewayCallException.class)
            .satisfies(e -> assertThat(((GatewayCallException) e).code()).isEqualTo("OVERLOADED"));
        assertThat(seen).hasSize(1);
        assertThat(sleeps).isEmpty();
        assertThat(logged).extracting(Attempt::outcome).containsExactly(Outcome.REFUSED);
    }

    @Test
    void connectionRefusedIsNotSentAndIsRetriedOnce() {
        server.stop(0);
        GatewayProperties unreachable = new GatewayProperties(true, "http://127.0.0.1:1", properties.tokenUri(),
            "reconcile-ai", "secret", Duration.ofSeconds(1), Duration.ofSeconds(2));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        GatewayChatPort port = new HttpGatewayChatAdapter(http, json, unreachable, () -> "svc-token-1",
            Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add, logged::add);

        assertThatThrownBy(() -> port.chat(request(NOW.plusSeconds(120))))
            .isInstanceOf(GatewayCallException.class)
            .satisfies(e -> assertThat(((GatewayCallException) e).code()).isEqualTo("TRANSPORT_NOT_SENT"));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(logged).extracting(Attempt::outcome).containsExactly(Outcome.NOT_SENT, Outcome.NOT_SENT);
        assertThat(logged).extracting(Attempt::attempt).containsExactly(1, 2);
        assertThat(logged).extracting(Attempt::httpStatus).containsOnly(0);
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Sends the scripted reply, after checking it against the published response or error schema. */
    private void respond(HttpExchange ex, int status, String body, String retryAfter) throws IOException {
        if (ex.getRequestURI().getPath().endsWith("/chat")) {
            JsonSchema expected = status == 200 ? responseSchema : errorSchema;
            Set<ValidationMessage> violations = expected.validate(json.readTree(body));
            assertThat(violations).as("fake reply must match the gateway contract: " + body).isEmpty();
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

    @Test
    void aFailingTokenEndpointIsATypedNotSentRefusal() {
        HttpClient http = HttpClient.newBuilder().build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        GatewayChatPort port = new HttpGatewayChatAdapter(http, json, properties,
            () -> { throw new IllegalStateException("GATEWAY_TOKEN_UNAVAILABLE: token endpoint returned 503"); },
            clock, sleeps::add, logged::add);

        assertThatThrownBy(() -> port.chat(request(NOW.plusSeconds(60))))
            .isInstanceOf(com.reconcileai.application.gateway.GatewayCallException.class)
            .satisfies(e -> assertThat(((com.reconcileai.application.gateway.GatewayCallException) e).code())
                .isEqualTo("TOKEN_UNAVAILABLE"));
        assertThat(seen).as("nothing reached the gateway").isEmpty();
        assertThat(logged).singleElement().satisfies(a -> {
            assertThat(a.outcome()).isEqualTo(Outcome.NOT_SENT);
            assertThat(a.code()).isEqualTo("TOKEN_UNAVAILABLE");
        });
    }
}
