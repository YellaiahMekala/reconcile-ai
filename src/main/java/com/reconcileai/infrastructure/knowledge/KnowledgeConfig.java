// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.knowledge.KnowledgeSearchPort;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.http.HttpClient;
import java.time.Clock;

/**
 * Builds the EKA knowledge lookup only when {@code reconcile.knowledge.enabled=true}. With the default
 * ({@code KNOWLEDGE_ENABLED=false}) no {@link KnowledgeSearchPort} bean exists and investigations run exactly as
 * before: no knowledge columns are written and the prompt is unchanged.
 */
@Configuration
@ConditionalOnProperty(prefix = "reconcile.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeConfig {

    @Bean
    ServiceTokenProvider knowledgeTokenProvider(ObjectMapper objectMapper, KnowledgeProperties properties,
                                                Environment environment) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        return new TenantServiceTokens(http, objectMapper, properties, environment::getProperty, Clock.systemUTC());
    }

    @Bean
    KnowledgeSearchPort knowledgeSearchPort(ObjectMapper objectMapper, KnowledgeProperties properties,
                                            ServiceTokenProvider knowledgeTokenProvider, InjectionScanner scanner,
                                            ObjectProvider<MeterRegistry> meters) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        MeterRegistry registry = meters.getIfAvailable();
        return new EkaKnowledgeClient(http, objectMapper, properties, knowledgeTokenProvider, scanner,
                (outcome, reason) -> {
                    if (registry != null) {
                        registry.counter("reconcile.knowledge.outcome", "outcome", outcome, "reason", reason).increment();
                    }
                });
    }
}
