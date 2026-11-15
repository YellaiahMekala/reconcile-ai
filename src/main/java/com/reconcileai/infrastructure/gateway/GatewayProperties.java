package com.reconcileai.infrastructure.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code reconcile.gateway.*}. Off by default ({@code GATEWAY_ENABLED=false}): the investigation keeps its direct
 * model path until the gateway route is proven (platform Q2).
 *
 * @param tokenUri     the realm's token endpoint; the client credentials grant gets a service token for
 *                     {@code clientId}. The realm adds {@code aud: ai-gateway}, {@code tenant_id} and a top-level
 *                     {@code roles: [GATEWAY_CONSUMER]} (ai-gateway {@code SERVICE_TOKEN_READINESS.md})
 * @param requestTimeout per HTTP attempt; a timeout after the request was sent is an unknown outcome, never retried
 */
@ConfigurationProperties("reconcile.gateway")
public record GatewayProperties(boolean enabled, String baseUrl, String tokenUri, String clientId,
                                String clientSecret, Duration connectTimeout, Duration requestTimeout) {
}
