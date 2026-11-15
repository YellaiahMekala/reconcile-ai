package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayCallException;
import com.reconcileai.application.gateway.GatewayChatPort;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link GatewayChatPort} over HTTP: {@code POST /api/v1/chat} with a service token and the caller's idempotency
 * key (ai-gateway CONSUMER_CONTRACT.md).
 * <p>
 * Retry rule (contract §4): at most one transport retry, with the <b>same key and the same body</b>, only for codes
 * where nothing reached the provider or the gateway asks for one, and only if the retry can finish before the
 * caller's deadline. Never on 504 UNKNOWN_OUTCOME, 409 IDEMPOTENCY_UNKNOWN_OUTCOME or 429 BUDGET_BLOCKED, and never
 * after a timeout once the request was sent: the gateway may have executed it.
 */
public class HttpGatewayChatAdapter implements GatewayChatPort {

    static final String TASK_CLASS = "REASONING";
    static final Set<String> RETRYABLE = Set.of("NOT_SENT", "RETRYABLE_FAILURE", "OVERLOADED", "MODEL_UNAVAILABLE",
            "GOVERNANCE_UNAVAILABLE", "TENANT_CONCURRENCY_LIMIT", "PROVIDER_CONTRACT_FAILURE", "TRANSPORT_NOT_SENT");
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 5;

    /** Waits between attempts; replaced in tests. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpClient http;
    private final ObjectMapper json;
    private final GatewayProperties properties;
    private final Supplier<String> tokens;
    private final Clock clock;
    private final Sleeper sleeper;

    public HttpGatewayChatAdapter(HttpClient http, ObjectMapper json, GatewayProperties properties,
                                  Supplier<String> tokens, Clock clock, Sleeper sleeper) {
        this.http = http;
        this.json = json;
        this.properties = properties;
        this.tokens = tokens;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public Reply chat(Request request) {
        String body = body(request);
        try {
            return send(request, body);
        } catch (Refusal first) {
            if (!RETRYABLE.contains(first.code()) || first.retryAfter == null) {
                throw first;
            }
            if (clock.instant().plus(first.retryAfter).plus(properties.requestTimeout()).isAfter(request.deadline())) {
                throw first;
            }
            try {
                sleeper.sleep(first.retryAfter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return send(request, body);
        }
    }

    private Reply send(Request request, String body) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/api/v1/chat"))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + tokens.get())
                .header("Idempotency-Key", request.idempotencyKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new Refusal(0, "TRANSPORT_NOT_SENT", null, Duration.ofSeconds(1));
        } catch (IOException e) {
            // Sent (or possibly sent) and no answer: the gateway may have run the call. Never retried.
            throw new Refusal(0, "TRANSPORT_UNKNOWN_OUTCOME", null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refusal(0, "TRANSPORT_UNKNOWN_OUTCOME", null, null);
        }
        JsonNode node = parse(response.body());
        if (response.statusCode() == 200) {
            JsonNode usage = node.path("usage");
            return new Reply(uuid(node.path("invocationId")), node.path("status").asText(),
                    node.path("output").asText(""), usage.path("inputTokens").asInt(), usage.path("outputTokens").asInt());
        }
        Duration retryAfter = response.headers().firstValue("Retry-After").map(Long::parseLong).map(Duration::ofSeconds)
                .orElse(Duration.ofSeconds(DEFAULT_RETRY_AFTER_SECONDS));
        throw new Refusal(response.statusCode(), node.path("code").asText("UNKNOWN_ERROR"),
                uuid(node.path("invocationId")), retryAfter);
    }

    private String body(Request request) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("maxOutputTokens", request.maxOutputTokens());
        settings.put("temperature", request.temperature());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskClass", TASK_CLASS);
        body.put("messages", List.of(Map.of("role", "SYSTEM", "content", request.system()),
                Map.of("role", "USER", "content", request.user())));
        body.put("settings", settings);
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise the gateway request", e);
        }
    }

    private JsonNode parse(String text) {
        try {
            return text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private static UUID uuid(JsonNode node) {
        return node.isTextual() ? UUID.fromString(node.asText()) : null;
    }

    /** A refusal that remembers the server's Retry-After for the one permitted retry. */
    static final class Refusal extends GatewayCallException {
        final Duration retryAfter;

        Refusal(int status, String code, UUID invocationId, Duration retryAfter) {
            super(status, code, invocationId);
            this.retryAfter = retryAfter;
        }
    }
}
