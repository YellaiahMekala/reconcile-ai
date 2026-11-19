// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayCallException;
import com.reconcileai.application.gateway.GatewayCallLog;
import com.reconcileai.application.gateway.GatewayCallLog.Attempt;
import com.reconcileai.application.gateway.GatewayCallLog.Outcome;
import com.reconcileai.application.gateway.GatewayChatPort;
import com.reconcileai.application.gateway.GatewayFailure;

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
 * <p>
 * Every attempt is written to the {@link GatewayCallLog} (platform Q2.4) so a job can be reconciled against the
 * gateway's ledger: attempt 1 and, when the rule allows it, attempt 2 under the same key.
 */
public class HttpGatewayChatAdapter implements GatewayChatPort {

    static final String TASK_CLASS = "REASONING";
    static final Set<String> RETRYABLE = Set.of("NOT_SENT", "RETRYABLE_FAILURE", "OVERLOADED", "MODEL_UNAVAILABLE",
            "GOVERNANCE_UNAVAILABLE", "TENANT_CONCURRENCY_LIMIT", "PROVIDER_CONTRACT_FAILURE", "TRANSPORT_NOT_SENT");
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 5;
    static final String TOKEN_UNAVAILABLE = "TOKEN_UNAVAILABLE";

    /** Waits between attempts; replaced in tests. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpClient http;
    private final ObjectMapper json;
    private final GatewayProperties properties;
    private final GatewayTokenProvider tokens;
    private final Clock clock;
    private final Sleeper sleeper;
    private final GatewayCallLog callLog;

    public HttpGatewayChatAdapter(HttpClient http, ObjectMapper json, GatewayProperties properties,
                                  Supplier<String> tokens, Clock clock, Sleeper sleeper) {
        this(http, json, properties, GatewayTokenProvider.shared(tokens), clock, sleeper, GatewayCallLog.NOOP);
    }

    @SuppressWarnings("java:S107")
    public HttpGatewayChatAdapter(HttpClient http, ObjectMapper json, GatewayProperties properties,
                                  Supplier<String> tokens, Clock clock, Sleeper sleeper, GatewayCallLog callLog) {
        this(http, json, properties, GatewayTokenProvider.shared(tokens), clock, sleeper, callLog);
    }

    /** @param tokens the job's tenant picks the token (per-tenant clients), or one shared client for all */
    @SuppressWarnings("java:S107")
    public HttpGatewayChatAdapter(HttpClient http, ObjectMapper json, GatewayProperties properties,
                                  GatewayTokenProvider tokens, Clock clock, Sleeper sleeper, GatewayCallLog callLog) {
        this.http = http;
        this.json = json;
        this.properties = properties;
        this.tokens = tokens;
        this.clock = clock;
        this.sleeper = sleeper;
        this.callLog = callLog;
    }

    @Override
    public Reply chat(Request request) {
        String body = body(request);
        try {
            return send(request, body, 1);
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
            return send(request, body, 2);
        }
    }

    private Reply send(Request request, String body, int attempt) {
        long started = System.nanoTime();
        String token;
        try {
            token = tokens.token(request.tenantId());
        } catch (GatewayCallException refusedBeforeSending) {
            // No client for this tenant, or the token belongs to another tenant: nothing left this process.
            callLog.record(new Attempt(request.tenantId(), request.jobId(), request.idempotencyKey(), attempt,
                    Outcome.NOT_SENT, 0, refusedBeforeSending.code(), null, null, 0, 0, millisSince(started)));
            throw refusedBeforeSending;
        } catch (RuntimeException tokenEndpoint) {
            // Token endpoint down or answering garbage: still nothing sent, and the job reason must say so.
            GatewayCallException e = new GatewayCallException(0, TOKEN_UNAVAILABLE, null);
            callLog.record(new Attempt(request.tenantId(), request.jobId(), request.idempotencyKey(), attempt,
                    Outcome.NOT_SENT, 0, TOKEN_UNAVAILABLE, null, null, 0, 0, millisSince(started)));
            throw e;
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/api/v1/chat"))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", request.idempotencyKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            Refusal r = new Refusal(0, "TRANSPORT_NOT_SENT", null, Duration.ofSeconds(1));
            log(request, attempt, Outcome.NOT_SENT, r, null, started);
            throw r;
        } catch (IOException e) {
            // Sent (or possibly sent) and no answer: the gateway may have run the call. Never retried.
            Refusal r = new Refusal(0, "TRANSPORT_UNKNOWN_OUTCOME", null, null);
            log(request, attempt, Outcome.UNKNOWN, r, null, started);
            throw r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Refusal r = new Refusal(0, "TRANSPORT_UNKNOWN_OUTCOME", null, null);
            log(request, attempt, Outcome.UNKNOWN, r, null, started);
            throw r;
        }
        JsonNode node = parse(response.body());
        if (response.statusCode() == 200) {
            JsonNode usage = node.path("usage");
            Reply reply = new Reply(uuid(node.path("invocationId")), node.path("status").asText(),
                    node.path("output").asText(""), usage.path("inputTokens").asInt(), usage.path("outputTokens").asInt());
            callLog.record(new Attempt(request.tenantId(), request.jobId(), request.idempotencyKey(), attempt,
                    Outcome.SUCCESS, 200, null, reply.invocationId(), reply.status(), reply.inputTokens(),
                    reply.outputTokens(), millisSince(started)));
            return reply;
        }
        Duration retryAfter = response.headers().firstValue("Retry-After").map(Long::parseLong).map(Duration::ofSeconds)
                .orElse(Duration.ofSeconds(DEFAULT_RETRY_AFTER_SECONDS));
        Refusal r = new Refusal(response.statusCode(), node.path("code").asText("UNKNOWN_ERROR"),
                uuid(node.path("invocationId")), retryAfter);
        log(request, attempt, GatewayFailure.outcomeUnknown(r) ? Outcome.UNKNOWN : Outcome.REFUSED, r,
                node.path("status").isTextual() ? node.path("status").asText() : null, started);
        throw r;
    }

    private void log(Request request, int attempt, Outcome outcome, Refusal r, String gatewayStatus, long started) {
        callLog.record(new Attempt(request.tenantId(), request.jobId(), request.idempotencyKey(), attempt, outcome,
                r.httpStatus(), r.code(), r.invocationId(), gatewayStatus, 0, 0, millisSince(started)));
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
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
        if (!node.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
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
