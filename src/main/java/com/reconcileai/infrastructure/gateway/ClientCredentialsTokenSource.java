package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A service token for ai-gateway from the realm's client credentials grant, cached until 30 s before it expires.
 * One instance per OAuth client. The secret and the token are never logged.
 */
public class ClientCredentialsTokenSource implements Supplier<String> {

    private static final long REFRESH_MARGIN_SECONDS = 30;

    private final HttpClient http;
    private final ObjectMapper json;
    private final String tokenUri;
    private final String clientId;
    private final Supplier<String> clientSecret;
    private final String tokenScope;
    private final Duration timeout;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.MIN;

    /** The single shared client from {@code reconcile.gateway.client-id} / {@code client-secret}. */
    public ClientCredentialsTokenSource(HttpClient http, ObjectMapper json, GatewayProperties properties, Clock clock) {
        this(http, json, properties.tokenUri(), properties.clientId(), properties::clientSecret,
                properties.tokenScope(), properties.requestTimeout(), clock);
    }

    /**
     * @param clientSecret read on each grant, so a rotated secret (environment or secret store) is picked up at the
     *                     next refresh without a restart of this object
     */
    @SuppressWarnings("java:S107")
    public ClientCredentialsTokenSource(HttpClient http, ObjectMapper json, String tokenUri, String clientId,
                                        Supplier<String> clientSecret, String tokenScope, Duration timeout,
                                        Clock clock) {
        this.http = http;
        this.json = json;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenScope = tokenScope;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        if (token != null && clock.instant().isBefore(refreshAt)) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret.get());
        if (tokenScope != null && !tokenScope.isBlank()) {
            // The platform realm adds the gateway audience only for this scope (spike S-3); without it the gateway says 401.
            form += "&scope=" + encode(tokenScope.strip());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GATEWAY_TOKEN_UNAVAILABLE: token endpoint returned "
                        + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            String accessToken = body.path("access_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(60);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("GATEWAY_TOKEN_UNAVAILABLE: no access_token in the response");
            }
            token = accessToken;
            refreshAt = clock.instant().plusSeconds(Math.max(0, expiresIn - REFRESH_MARGIN_SECONDS));
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("GATEWAY_TOKEN_UNAVAILABLE: token endpoint unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GATEWAY_TOKEN_UNAVAILABLE: interrupted", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
