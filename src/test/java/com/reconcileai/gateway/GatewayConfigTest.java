package com.reconcileai.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayChatPort;
import com.reconcileai.infrastructure.gateway.GatewayConfig;
import com.reconcileai.infrastructure.gateway.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** The gateway route exists only when {@code reconcile.gateway.enabled=true}; the default is off. */
class GatewayConfigTest {

    @Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class Props { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Props.class, GatewayConfig.class)
        .withBean(ObjectMapper.class)
        .withPropertyValues(
            "reconcile.gateway.base-url=http://localhost:8080",
            "reconcile.gateway.token-uri=http://localhost:8180/token",
            "reconcile.gateway.client-id=reconcile-ai",
            "reconcile.gateway.client-secret=",
            "reconcile.gateway.connect-timeout=3s",
            "reconcile.gateway.request-timeout=60s");

    @Test
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(GatewayChatPort.class));
    }

    @Test
    void explicitlyDisabled() {
        runner.withPropertyValues("reconcile.gateway.enabled=false")
            .run(ctx -> assertThat(ctx).doesNotHaveBean(GatewayChatPort.class));
    }

    @Test
    void enabledBuildsTheHttpAdapter() {
        runner.withPropertyValues("reconcile.gateway.enabled=true")
            .run(ctx -> assertThat(ctx).hasSingleBean(GatewayChatPort.class));
    }
}
