package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayLedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * {@link GatewayLedgerPort} over HTTP with the job tenant's service token. One attempt, no retry: a failure is
 * {@link Result#UNAVAILABLE} and the caller decides (the reconcile endpoint answers 503 and the person retries or
 * uses a ticket reference). Any token failure, including a tenant with no client, is also UNAVAILABLE: the lookup
 * cannot vouch for anything it could not read.
 */
public class HttpGatewayLedgerAdapter implements GatewayLedgerPort {

    private static final Logger log = LoggerFactory.getLogger(HttpGatewayLedgerAdapter.class);

    private final HttpClient http;
    private final ObjectMapper json;
    private final GatewayProperties properties;
    private final GatewayTokenProvider tokens;

    public HttpGatewayLedgerAdapter(HttpClient http, ObjectMapper json, GatewayProperties properties,
                                    GatewayTokenProvider tokens) {
        this.http = http;
        this.json = json;
        this.properties = properties;
        this.tokens = tokens;
    }

    @Override
    public Lookup lookup(String tenantId, UUID invocationId) {
        String token;
        try {
            token = tokens.token(tenantId);
        } catch (RuntimeException e) {
            log.warn("Gateway ledger lookup for {} not possible: no service token ({})", invocationId, codeOf(e));
            return Lookup.unavailable();
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(properties.baseUrl() + "/api/v1/invocations/" + invocationId))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Gateway ledger lookup for {} failed: {}", invocationId, e.getClass().getSimpleName());
            return Lookup.unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Lookup.unavailable();
        }
        if (response.statusCode() == 404) {
            return Lookup.notFound();
        }
        if (response.statusCode() != 200) {
            log.warn("Gateway ledger lookup for {} answered {}", invocationId, response.statusCode());
            return Lookup.unavailable();
        }
        try {
            JsonNode v = json.readTree(response.body());
            UUID id = UUID.fromString(v.path("invocationId").asText());
            if (!id.equals(invocationId)) {
                // A ledger that answers about a different invocation is not evidence for this one.
                return Lookup.unavailable();
            }
            return Lookup.found(new Entry(id, text(v, "status"), text(v, "executionCertainty"), text(v, "modelKey"),
                    text(v, "provider"), v.path("inputTokens").asInt(0), v.path("outputTokens").asInt(0),
                    text(v, "usageSource"), v.path("actualCostUsd").isNumber() ? v.path("actualCostUsd").decimalValue() : null,
                    instant(v.path("createdAt"))));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Gateway ledger lookup for {} returned an unreadable body", invocationId);
            return Lookup.unavailable();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isTextual() ? f.asText() : null;
    }

    private static Instant instant(JsonNode node) {
        try {
            return node.isTextual() ? Instant.parse(node.asText()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String codeOf(RuntimeException e) {
        return e instanceof com.reconcileai.application.gateway.GatewayCallException g ? g.code() : e.getClass().getSimpleName();
    }
}
