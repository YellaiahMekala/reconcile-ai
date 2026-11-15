package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayChatPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.function.Supplier;

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

    @Bean
    Supplier<String> gatewayTokenSource(HttpClient gatewayHttpClient, ObjectMapper objectMapper,
                                        GatewayProperties properties) {
        return new ClientCredentialsTokenSource(gatewayHttpClient, objectMapper, properties, Clock.systemUTC());
    }

    @Bean
    GatewayChatPort gatewayChatPort(HttpClient gatewayHttpClient, ObjectMapper objectMapper,
                                    GatewayProperties properties, Supplier<String> gatewayTokenSource) {
        return new HttpGatewayChatAdapter(gatewayHttpClient, objectMapper, properties, gatewayTokenSource,
                Clock.systemUTC(), Thread::sleep);
    }
}
