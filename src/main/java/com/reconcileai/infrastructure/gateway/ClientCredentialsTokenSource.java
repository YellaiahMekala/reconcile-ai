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
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A service token for ai-gateway from the realm's client credentials grant, cached until 30 s before it expires.
 * The secret and the token are never logged.
 */
public class ClientCredentialsTokenSource implements Supplier<String> {

    private static final long REFRESH_MARGIN_SECONDS = 30;

    private final HttpClient http;
    private final ObjectMapper json;
    private final GatewayProperties properties;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.MIN;

    public ClientCredentialsTokenSource(HttpClient http, ObjectMapper json, GatewayProperties properties, Clock clock) {
        this.http = http;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        if (token != null && clock.instant().isBefore(refreshAt)) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(properties.clientId())
                + "&client_secret=" + encode(properties.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.tokenUri()))
                .timeout(properties.requestTimeout())
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
