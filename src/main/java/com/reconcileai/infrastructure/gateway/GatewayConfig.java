// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayCallLog;
import com.reconcileai.application.gateway.GatewayChatPort;
import com.reconcileai.application.gateway.GatewayLedgerPort;
import com.reconcileai.infrastructure.persistence.GatewayCallRepository;
import com.reconcileai.infrastructure.persistence.LlmCallRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpClient;
import java.time.Clock;

/**
 * Builds the ai-gateway route only when {@code reconcile.gateway.enabled=true}. With the default
 * ({@code GATEWAY_ENABLED=false}) no {@link GatewayChatPort} bean exists and the investigation keeps its direct
 * model path.
 */
@Configuration
@ConditionalOnProperty(prefix = "reconcile.gateway", name = "enabled", havingValue = "true")
public class GatewayConfig {

    @Bean
    HttpClient gatewayHttpClient(GatewayProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    /**
     * Shared client (default) or one client per tenant ({@code reconcile.gateway.tenants}). Per-tenant secrets are
     * resolved through the Spring environment from the variable each entry names, so they never sit in YAML.
     */
    @Bean
    GatewayTokenProvider gatewayTokenProvider(HttpClient gatewayHttpClient, ObjectMapper objectMapper,
                                              GatewayProperties properties, Environment environment) {
        if (properties.perTenantClients()) {
            return new TenantGatewayTokens(gatewayHttpClient, objectMapper, properties, environment::getProperty,
                    Clock.systemUTC());
        }
        return GatewayTokenProvider.shared(
                new ClientCredentialsTokenSource(gatewayHttpClient, objectMapper, properties, Clock.systemUTC()));
    }

    /** Our side of the ledger (Q2.4). In a slice without JPA (config tests) it is a no-op. */
    @Bean
    GatewayCallLog gatewayCallLog(ObjectProvider<GatewayCallRepository> calls, ObjectProvider<LlmCallRepository> llmCalls,
                                  ObjectProvider<TransactionTemplate> tx) {
        GatewayCallRepository repo = calls.getIfAvailable();
        LlmCallRepository llm = llmCalls.getIfAvailable();
        TransactionTemplate template = tx.getIfAvailable();
        if (repo == null || llm == null || template == null) {
            return GatewayCallLog.NOOP;
        }
        return new JpaGatewayCallLog(repo, llm, template);
    }

    @Bean
    GatewayChatPort gatewayChatPort(HttpClient gatewayHttpClient, ObjectMapper objectMapper,
                                    GatewayProperties properties, GatewayTokenProvider gatewayTokenProvider,
                                    GatewayCallLog gatewayCallLog) {
        return new HttpGatewayChatAdapter(gatewayHttpClient, objectMapper, properties, gatewayTokenProvider,
                Clock.systemUTC(), Thread::sleep, gatewayCallLog);
    }

    /** The gateway's ledger, read with the job tenant's token when an unknown outcome is reconciled. */
    @Bean
    GatewayLedgerPort gatewayLedgerPort(HttpClient gatewayHttpClient, ObjectMapper objectMapper,
                                        GatewayProperties properties, GatewayTokenProvider gatewayTokenProvider) {
        return new HttpGatewayLedgerAdapter(gatewayHttpClient, objectMapper, properties, gatewayTokenProvider);
    }
}
