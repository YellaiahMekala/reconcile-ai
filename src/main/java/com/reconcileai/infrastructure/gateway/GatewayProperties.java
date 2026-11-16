package com.reconcileai.infrastructure.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.Map;

/**
 * {@code reconcile.gateway.*}. Off by default ({@code GATEWAY_ENABLED=false}): the investigation keeps its direct
 * model path until the gateway route is proven (platform Q2).
 *
 * @param tokenUri     the realm's token endpoint; the client credentials grant gets a service token for
 *                     {@code clientId}. The realm adds {@code aud: ai-gateway}, {@code tenant_id} and a top-level
 *                     {@code roles: [GATEWAY_CONSUMER]} (ai-gateway {@code SERVICE_TOKEN_READINESS.md})
 * @param tokenScope   OAuth scope sent with the grant. The platform realm only adds the gateway audience when the
 *                     client asks for {@code aud-ai-gateway} (platform spike S-3); blank sends no scope
 * @param requestTimeout per HTTP attempt; a timeout after the request was sent is an unknown outcome, never retried
 * @param tenants      per-tenant OAuth clients, keyed by reconcile-ai tenant id. Empty (the default): every tenant
 *                     uses the shared {@code clientId}, so the gateway bills the shared client's tenant. Non-empty:
 *                     each call uses its job's tenant client, the token's {@code tenant_id} claim must equal the
 *                     job's tenant, and a tenant with no entry is refused before anything is sent. Use bracket keys
 *                     in YAML ({@code tenants: "[TENANT_A]": ...}) so the tenant id keeps its exact spelling
 */
@ConfigurationProperties("reconcile.gateway")
public record GatewayProperties(boolean enabled, String baseUrl, String tokenUri, String clientId,
                                String clientSecret, String tokenScope, Duration connectTimeout,
                                Duration requestTimeout, Map<String, TenantClient> tenants) {

    /**
     * One tenant's OAuth client. The secret itself is never in configuration: {@code clientSecretEnv} names the
     * environment variable (or any Spring property) that holds it.
     */
    public record TenantClient(String clientId, String clientSecretEnv) { }

    @ConstructorBinding
    public GatewayProperties {
        tenants = tenants == null ? Map.of() : Map.copyOf(tenants);
    }

    /** Convenience for tests and callers that predate per-tenant clients. */
    public GatewayProperties(boolean enabled, String baseUrl, String tokenUri, String clientId, String clientSecret,
                             String tokenScope, Duration connectTimeout, Duration requestTimeout) {
        this(enabled, baseUrl, tokenUri, clientId, clientSecret, tokenScope, connectTimeout, requestTimeout, Map.of());
    }

    /** Convenience for tests and callers that predate {@code tokenScope}. */
    public GatewayProperties(boolean enabled, String baseUrl, String tokenUri, String clientId, String clientSecret,
                             Duration connectTimeout, Duration requestTimeout) {
        this(enabled, baseUrl, tokenUri, clientId, clientSecret, "", connectTimeout, requestTimeout, Map.of());
    }

    public boolean perTenantClients() {
        return !tenants.isEmpty();
    }
}
