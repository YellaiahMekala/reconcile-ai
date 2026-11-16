package com.reconcileai.infrastructure.gateway;

import java.util.function.Supplier;

/**
 * A service token for ai-gateway, chosen by the tenant the call is made for. The gateway bills and scopes its ledger
 * by the token's {@code tenant_id} claim, so the token must belong to the job's tenant (see
 * {@link TenantGatewayTokens}). A failure that means "nothing was sent" is a
 * {@link com.reconcileai.application.gateway.GatewayCallException} with HTTP status 0.
 */
@FunctionalInterface
public interface GatewayTokenProvider {

    String token(String tenantId);

    /** One client for every tenant: the original behaviour, kept for single-tenant set-ups and unit tests. */
    static GatewayTokenProvider shared(Supplier<String> tokens) {
        return tenantId -> tokens.get();
    }
}
