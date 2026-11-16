package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayCallException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-tenant service tokens for ai-gateway.
 * <p>
 * The gateway attributes spend, budgets and its ledger to the {@code tenant_id} claim of the token. With one shared
 * client every job is billed to that client's tenant, whatever tenant the job belongs to. Here each reconcile-ai
 * tenant has its own OAuth client ({@code reconcile.gateway.tenants}), and before any call leaves:
 * <ol>
 *   <li>a tenant with no client is refused: {@code TENANT_CREDENTIALS_MISSING}; there is no fallback to a shared
 *       client, because that would bill another tenant;</li>
 *   <li>the token's {@code tenant_id} claim must equal the job's tenant: {@code TOKEN_TENANT_MISMATCH}, or
 *       {@code TOKEN_TENANT_UNVERIFIED} when the token carries no readable claim.</li>
 * </ol>
 * Both are {@link GatewayCallException}s with HTTP status 0: nothing was sent, nothing was billed. The claim is read
 * without verifying the signature: the token comes straight from our own token endpoint over the configured URI, and
 * the gateway verifies the signature itself. The check catches a mis-mapped client, not a forged token.
 * <p>
 * Secrets are read through {@code secrets} (Spring {@code Environment::getProperty}) from the variable named in
 * {@code clientSecretEnv}, at start-up (fail fast if one is missing) and again at each token refresh.
 */
public class TenantGatewayTokens implements GatewayTokenProvider {

    static final String MISSING = "TENANT_CREDENTIALS_MISSING";
    static final String MISMATCH = "TOKEN_TENANT_MISMATCH";
    static final String UNVERIFIED = "TOKEN_TENANT_UNVERIFIED";
    private static final String TENANT_CLAIM = "tenant_id";

    private final Map<String, GatewayProperties.TenantClient> exact;
    private final Map<String, String> normalisedToExact = new TreeMap<>();
    private final Map<String, Supplier<String>> sources = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final Function<String, Supplier<String>> sourceFactory;

    public TenantGatewayTokens(HttpClient http, ObjectMapper json, GatewayProperties properties,
                               Function<String, String> secrets, Clock clock) {
        this(json, properties.tenants(), secrets, client -> new ClientCredentialsTokenSource(http, json,
                properties.tokenUri(), client.clientId(), () -> secrets.apply(client.clientSecretEnv()),
                properties.tokenScope(), properties.requestTimeout(), clock));
    }

    /** For tests: {@code sourceFactory} builds the token source for one tenant's client. */
    TenantGatewayTokens(ObjectMapper json, Map<String, GatewayProperties.TenantClient> tenants,
                        Function<String, String> secrets,
                        Function<GatewayProperties.TenantClient, Supplier<String>> sourceFactory) {
        if (tenants.isEmpty()) {
            throw new IllegalArgumentException("per-tenant gateway tokens need at least one reconcile.gateway.tenants entry");
        }
        this.json = json;
        this.exact = Map.copyOf(tenants);
        List<String> problems = new ArrayList<>();
        tenants.forEach((tenant, client) -> {
            if (client == null || blank(client.clientId()) || blank(client.clientSecretEnv())) {
                problems.add(tenant + ": client-id and client-secret-env are required");
            } else if (blank(secrets.apply(client.clientSecretEnv()))) {
                problems.add(tenant + ": " + client.clientSecretEnv() + " is not set");
            }
            String previous = normalisedToExact.put(normalise(tenant), tenant);
            if (previous != null) {
                problems.add(tenant + ": collides with " + previous + " once case and separators are ignored");
            }
        });
        if (!problems.isEmpty()) {
            // Tenant ids and variable names only; never a secret value.
            throw new IllegalStateException("reconcile.gateway.tenants is incomplete: " + String.join("; ", problems));
        }
        this.sourceFactory = tenant -> sourceFactory.apply(exact.get(tenant));
    }

    @Override
    public String token(String tenantId) {
        String configured = configuredKey(tenantId);
        if (configured == null) {
            throw new GatewayCallException(0, MISSING, null);
        }
        String token = sources.computeIfAbsent(configured, sourceFactory).get();
        String claimed = tenantClaim(token);
        if (claimed == null) {
            throw new GatewayCallException(0, UNVERIFIED, null);
        }
        if (!claimed.equals(tenantId)) {
            throw new GatewayCallException(0, MISMATCH, null);
        }
        return token;
    }

    /**
     * Spring's relaxed binding may drop separators from map keys written without brackets ({@code TENANT_A} can
     * arrive as {@code TENANTA}), so a lookup falls back to a separator- and case-insensitive match. The claim check
     * still compares the exact tenant id, so a loose key match can never send a call under another tenant's token.
     */
    private String configuredKey(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        if (exact.containsKey(tenantId)) {
            return tenantId;
        }
        return normalisedToExact.get(normalise(tenantId));
    }

    private String tenantClaim(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claim = json.readTree(new String(payload, StandardCharsets.UTF_8)).path(TENANT_CLAIM);
            return claim.isTextual() ? claim.asText() : null;
        } catch (IllegalArgumentException | IOException notAJwt) {
            return null;
        }
    }

    private static String normalise(String tenant) {
        return tenant.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
