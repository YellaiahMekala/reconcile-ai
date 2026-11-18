package com.reconcileai.infrastructure.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.infrastructure.gateway.ClientCredentialsTokenSource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-tenant EKA service tokens from the platform realm (platform Q1.1).
 * <ol>
 *   <li>A tenant with no configured client: {@code TENANT_CREDENTIALS_MISSING}. There is no shared fallback client,
 *       because EKA would treat every tenant as one owner.</li>
 *   <li>The token endpoint fails: {@code TOKEN_UNAVAILABLE}. Tokens are cached per client until 30 s before expiry
 *       ({@link ClientCredentialsTokenSource}), and the secret is re-read from the environment on each refresh.</li>
 *   <li>The token's {@code tenant_id} claim must equal the tenant exactly: {@code TOKEN_TENANT_MISMATCH}, or
 *       {@code TOKEN_TENANT_UNVERIFIED} without a readable claim. The signature is not checked here (the token comes
 *       straight from our configured endpoint, and EKA's edge verifies it); the check catches a mis-mapped client.</li>
 * </ol>
 * Tenant keys match exactly. Unlike the gateway mapping there is no relaxed match: a near-miss tenant id must not
 * pick up another identity's documents. Secrets and tokens are never logged.
 */
public class TenantServiceTokens implements ServiceTokenProvider {

    static final String MISSING = "TENANT_CREDENTIALS_MISSING";
    static final String UNAVAILABLE = "TOKEN_UNAVAILABLE";
    static final String MISMATCH = "TOKEN_TENANT_MISMATCH";
    static final String UNVERIFIED = "TOKEN_TENANT_UNVERIFIED";

    private final Map<String, KnowledgeProperties.TenantClient> tenants;
    private final Map<String, Supplier<String>> sources = new ConcurrentHashMap<>();
    private final Function<KnowledgeProperties.TenantClient, Supplier<String>> sourceFactory;
    private final ObjectMapper json;

    public TenantServiceTokens(HttpClient http, ObjectMapper json, KnowledgeProperties properties,
                               Function<String, String> secrets, Clock clock) {
        this(json, properties.tenants(), secrets, client -> new ClientCredentialsTokenSource(http, json,
                properties.tokenUri(), client.clientId(), () -> secrets.apply(client.clientSecretEnv()),
                properties.tokenScope(), properties.requestTimeout(), clock));
    }

    /** For tests: {@code sourceFactory} builds the token source of one tenant's client. */
    TenantServiceTokens(ObjectMapper json, Map<String, KnowledgeProperties.TenantClient> tenants,
                        Function<String, String> secrets,
                        Function<KnowledgeProperties.TenantClient, Supplier<String>> sourceFactory) {
        List<String> problems = new ArrayList<>();
        tenants.forEach((tenant, client) -> {
            if (client == null || blank(client.clientId()) || blank(client.clientSecretEnv())) {
                problems.add(tenant + ": client-id and client-secret-env are required");
            } else if (blank(secrets.apply(client.clientSecretEnv()))) {
                problems.add(tenant + ": " + client.clientSecretEnv() + " is not set");
            }
        });
        if (!problems.isEmpty()) {
            // Tenant ids and variable names only, never a secret value. Fail at start-up, not on the first job.
            throw new IllegalStateException("reconcile.knowledge.tenants is incomplete: " + String.join("; ", problems));
        }
        this.tenants = Map.copyOf(tenants);
        this.json = json;
        this.sourceFactory = sourceFactory;
    }

    @Override
    public String token(String tenantId) {
        KnowledgeProperties.TenantClient client = tenantId == null ? null : tenants.get(tenantId);
        if (client == null) {
            throw new ServiceTokenException(MISSING);
        }
        String token;
        try {
            token = sources.computeIfAbsent(tenantId, t -> sourceFactory.apply(client)).get();
        } catch (RuntimeException e) {
            throw new ServiceTokenException(UNAVAILABLE);
        }
        String claimed = tenantClaim(token);
        if (claimed == null) {
            throw new ServiceTokenException(UNVERIFIED);
        }
        if (!claimed.equals(tenantId)) {
            throw new ServiceTokenException(MISMATCH);
        }
        return token;
    }

    private String tenantClaim(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            JsonNode claim = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                    .path("tenant_id");
            return claim.isTextual() ? claim.asText() : null;
        } catch (IllegalArgumentException | IOException notAJwt) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
