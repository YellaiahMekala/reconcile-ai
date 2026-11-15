package com.reconcileai.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.reconcileai.application.gateway.GatewayCallException;
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
 * the service token, the idempotency key format, and the retry rule from CONSUMER_CONTRACT.md §4.
 */
class HttpGatewayChatAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID JOB_ID = UUID.fromString("0f4b1c2e-6a7d-4f2a-9b3c-1d2e3f4a5b6c");

    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchema requestSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(getClass().getResourceAsStream("/contracts/ai-gateway/chat-request.v1.json"));

    private HttpServer server;
    private GatewayProperties properties;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final Deque<Scripted> chatReplies = new ArrayDeque<>();
    private final List<Seen> seen = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();

    private record Scripted(int status, String body, String retryAfter) { }

    private record Seen(String authorization, String idempotencyKey, String body) { }

    @BeforeEach
    void startFakeGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            tokenCalls.incrementAndGet();
            String form = read(ex.getRequestBody());
            assertThat(form).contains("grant_type=client_credentials").contains("client_id=reconcile-ai");
            respond(ex, 200, "{\"access_token\":\"svc-token-1\",\"token_type\":\"Bearer\",\"expires_in\":300}", null);
        });
        server.createContext("/api/v1/chat", ex -> {
            seen.add(new Seen(ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("Idempotency-Key"), read(ex.getRequestBody())));
            Scripted next = chatReplies.poll();
            if (next == null) {
                respond(ex, 500, "{\"code\":\"TEST_SCRIPT_EXHAUSTED\"}", null);
                return;
            }
            respond(ex, next.status(), next.body(), next.retryAfter());
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new GatewayProperties(true, base, base + "/token", "reconcile-ai", "secret",
            Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GatewayChatPort adapter(Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        ClientCredentialsTokenSource tokens = new ClientCredentialsTokenSource(http, json, properties, clock);
        return new HttpGatewayChatAdapter(http, json, properties, tokens, clock, sleeps::add);
    }

    private static Request request(Instant deadline) {
        return new Request(IdempotencyKeys.forStep(JOB_ID, "investigate", 1),
            "You are a reconciliation analyst.", "Investigate break B-1.", 700, 0.0, deadline);
    }

    private static String ok(String invocationId) {
        return "{\"invocationId\":\"" + invocationId + "\",\"correlationId\":\"c-1\",\"status\":\"SUCCESS\","
            + "\"output\":\"{\\\"ok\\\":true}\",\"usage\":{\"inputTokens\":12,\"outputTokens\":3},"
            + "\"replayOf\":null,\"cache\":\"MISS\",\"risk\":\"LOW\"}";
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"m\",\"correlationId\":\"c-1\",\"invocationId\":null,"
            + "\"timestamp\":\"2026-11-15T10:00:00Z\"}";
    }

    @Test
    void requestMatchesTheContractSchemaAndCarriesTokenAndKey() throws Exception {
        chatReplies.add(new Scripted(200, ok("11111111-1111-1111-1111-111111111111"), null));

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

        assertThat(reply.invocationId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(reply.status()).isEqualTo("SUCCESS");
        assertThat(reply.output()).isEqualTo("{\"ok\":true}");
        assertThat(reply.inputTokens()).isEqualTo(12);
        assertThat(reply.outputTokens()).isEqualTo(3);
    }

    @Test
    void serviceTokenIsFetchedOnceAndCachedAcrossCalls() {
        chatReplies.add(new Scripted(200, ok("11111111-1111-1111-1111-111111111111"), null));
        chatReplies.add(new Scripted(200, ok("22222222-2222-2222-2222-222222222222"), null));
        GatewayChatPort port = adapter(Clock.fixed(NOW, ZoneOffset.UTC));

        port.chat(request(NOW.plusSeconds(120)));
        port.chat(request(NOW.plusSeconds(120)));

        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(seen).extracting(Seen::authorization).containsExactly("Bearer svc-token-1", "Bearer svc-token-1");
    }

    @Test
    void retriesOnceOnNotSentWithTheSameKeyAndBody() {
        chatReplies.add(new Scripted(503, error("NOT_SENT"), "1"));
        chatReplies.add(new Scripted(200, ok("11111111-1111-1111-1111-111111111111"), null));

        Reply reply = adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(120)));

        assertThat(reply.status()).isEqualTo("SUCCESS");
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).idempotencyKey()).isEqualTo(seen.get(0).idempotencyKey());
        assertThat(seen.get(1).body()).isEqualTo(seen.get(0).body());
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void neverRetriesAnUnknownOutcome() {
        chatReplies.add(new Scripted(504, error("UNKNOWN_OUTCOME"), null));
        chatReplies.add(new Scripted(200, ok("11111111-1111-1111-1111-111111111111"), null));

        assertThatThrownBy(() -> adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(120))))
            .isInstanceOf(GatewayCallException.class)
            .satisfies(e -> {
                assertThat(((GatewayCallException) e).httpStatus()).isEqualTo(504);
                assertThat(((GatewayCallException) e).code()).isEqualTo("UNKNOWN_OUTCOME");
            });
        assertThat(seen).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void neverRetriesIdempotencyUnknownOutcomeOrBudgetBlocked() {
        for (String code : List.of("IDEMPOTENCY_UNKNOWN_OUTCOME", "BUDGET_BLOCKED")) {
            seen.clear();
            chatReplies.clear();
            chatReplies.add(new Scripted(code.startsWith("IDEMP") ? 409 : 429, error(code), "1"));
            chatReplies.add(new Scripted(200, ok("11111111-1111-1111-1111-111111111111"), null));

            assertThatThrownBy(() -> adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(120))))
                .isInstanceOf(GatewayCallException.class)
                .satisfies(e -> assertThat(((GatewayCallException) e).code()).isEqualTo(code));
            assertThat(seen).as(code).hasSize(1);
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void doesNotRetryWhenTheRetryCannotFinishBeforeTheDeadline() {
        chatReplies.add(new Scripted(503, error("OVERLOADED"), "5"));
        chatReplies.add(new Scripted(200, ok("11111111-1111-1111-1111-111111111111"), null));

        // Retry-After 5 s + request timeout 10 s would end after the 8 s deadline.
        assertThatThrownBy(() -> adapter(Clock.fixed(NOW, ZoneOffset.UTC)).chat(request(NOW.plusSeconds(8))))
            .isInstanceOf(GatewayCallException.class)
            .satisfies(e -> assertThat(((GatewayCallException) e).code()).isEqualTo("OVERLOADED"));
        assertThat(seen).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void connectionRefusedIsNotSentAndIsRetriedOnce() {
        server.stop(0);
        GatewayProperties unreachable = new GatewayProperties(true, "http://127.0.0.1:1", properties.tokenUri(),
            "reconcile-ai", "secret", Duration.ofSeconds(1), Duration.ofSeconds(2));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        GatewayChatPort port = new HttpGatewayChatAdapter(http, json, unreachable, () -> "svc-token-1",
            Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);

        assertThatThrownBy(() -> port.chat(request(NOW.plusSeconds(120))))
            .isInstanceOf(GatewayCallException.class)
            .satisfies(e -> assertThat(((GatewayCallException) e).code()).isEqualTo("TRANSPORT_NOT_SENT"));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) {
            ex.getResponseHeaders().add("Retry-After", retryAfter);
        }
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
