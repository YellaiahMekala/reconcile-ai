package com.reconcileai.infrastructure.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * {@code reconcile.knowledge.*}: EKA procedure knowledge for the single-call investigation (platform Q1). Off by
 * default ({@code KNOWLEDGE_ENABLED=false}).
 *
 * @param baseUrl        EKA <b>edge</b> base URL; only {@code POST /api/v1/search} is called
 * @param tokenUri       platform realm token endpoint (client credentials grant)
 * @param tokenScope     scope that adds {@code aud: eka-api} ({@code aud-eka-api} in the platform realm)
 * @param requestTimeout per call, token grant included; knowledge is advisory, so this stays short
 * @param topK           hits asked from EKA (1 to 20, EKA's own limit)
 * @param maxSnippetChars characters kept from one hit
 * @param maxTotalChars  characters of knowledge allowed into one prompt, all hits together
 * @param failClosed     {@code KNOWLEDGE_FAIL_CLOSED}: false (default) continues with the outcome recorded; true fails
 *                       the job retryably when knowledge is UNAVAILABLE
 * @param breakerFailureRate      % failures in the window that opens the breaker
 * @param breakerWindow           calls in the sliding window
 * @param breakerOpenFor          how long the breaker stays open before probing
 * @param tenants        one OAuth client per reconcile-ai tenant, required. EKA isolates by caller identity (spike
 *                       S-2), so there is no shared client: a tenant without an entry gets UNAVAILABLE
 *                       ({@code TENANT_CREDENTIALS_MISSING}) and nothing is sent
 */
@ConfigurationProperties("reconcile.knowledge")
public record KnowledgeProperties(boolean enabled, String baseUrl, String tokenUri, String tokenScope,
                                  Duration connectTimeout, Duration requestTimeout, int topK, int maxSnippetChars,
                                  int maxTotalChars, boolean failClosed, int breakerFailureRate, int breakerWindow,
                                  Duration breakerOpenFor, Map<String, TenantClient> tenants) {

    /** {@code clientSecretEnv} names the variable that holds the secret; the secret never sits in configuration. */
    public record TenantClient(String clientId, String clientSecretEnv) { }

    public KnowledgeProperties {
        tenants = tenants == null ? Map.of() : Map.copyOf(tenants);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        breakerOpenFor = breakerOpenFor == null ? Duration.ofSeconds(30) : breakerOpenFor;
        topK = topK <= 0 ? 3 : Math.min(topK, 20);
        maxSnippetChars = maxSnippetChars <= 0 ? 800 : maxSnippetChars;
        maxTotalChars = maxTotalChars <= 0 ? 2400 : maxTotalChars;
        breakerFailureRate = breakerFailureRate <= 0 ? 50 : breakerFailureRate;
        breakerWindow = breakerWindow <= 0 ? 10 : breakerWindow;
    }
}
