// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.rero;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.rero.ReroClientPort;
import com.reconcileai.application.rero.ReroInvestigation;
import com.reconcileai.application.rero.ReroRunStore;
import com.reconcileai.infrastructure.knowledge.TenantServiceTokens;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Set;

/**
 * Builds the RERO mode only when {@code reconcile.rero.enabled=true} (platform Q3.5). With the default no
 * {@link ReroInvestigation} bean exists and starting a RERO job is refused with 409 {@code RERO_DISABLED}.
 * The internal API's security chain and run-token keys live in {@link ReroInternalSecurityConfig} and are always
 * present (without keys they reject every request).
 */
@Configuration
@ConditionalOnProperty(prefix = "reconcile.rero", name = "enabled", havingValue = "true")
public class ReroConfig {

    @Bean
    ReroClientPort reroClient(ObjectMapper objectMapper, ReroProperties properties, Environment environment) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("reconcile.rero.base-url is required when reconcile.rero.enabled=true");
        }
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(properties.requestTimeout());
        RestClient rest = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        TenantServiceTokens tokens = new TenantServiceTokens(jdk, objectMapper, "reconcile.rero.tenants",
            properties.tokenUri(), properties.tokenScope(), properties.requestTimeout(), properties.tenants(),
            Set.copyOf(properties.requiredAudiences()), environment::getProperty, Clock.systemUTC());
        return new HttpReroClient(rest, objectMapper, tokens);
    }

    @Bean
    ReroInvestigation reroInvestigation(ReroClientPort reroClient, RunTokens runTokens, ReroRunStore store,
                                        ReroProperties properties) {
        return new ReroInvestigation(reroClient, runTokens, store, properties, Clock.systemUTC(), Thread::sleep);
    }
}
