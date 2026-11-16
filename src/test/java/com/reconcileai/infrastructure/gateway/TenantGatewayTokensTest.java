package com.reconcileai.infrastructure.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.gateway.GatewayCallException;
import com.reconcileai.infrastructure.gateway.GatewayProperties.TenantClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Per-tenant gateway credentials: refuse before sending, never fall back to another tenant's client. */
class TenantGatewayTokensTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String jwt(String tenantClaim) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String payload = tenantClaim == null ? "{\"azp\":\"x\"}" : "{\"tenant_id\":\"" + tenantClaim + "\"}";
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("GW_SECRET_A", "secret-a");
        env.put("GW_SECRET_B", "secret-b");
        return env;
    }

    private static Map<String, TenantClient> twoTenants() {
        Map<String, TenantClient> t = new LinkedHashMap<>();
        t.put("TENANT_A", new TenantClient("recon-tenant-a", "GW_SECRET_A"));
        t.put("TENANT_B", new TenantClient("recon-tenant-b", "GW_SECRET_B"));
        return t;
    }

    /** Each client's token claims the tenant its client id is registered for. */
    private static Function<TenantClient, Supplier<String>> realm(Map<String, String> clientToTenant, AtomicInteger built) {
        return client -> {
            built.incrementAndGet();
            return () -> jwt(clientToTenant.get(client.clientId()));
        };
    }

    @Test
    void eachTenantGetsItsOwnClientsToken() {
        AtomicInteger built = new AtomicInteger();
        TenantGatewayTokens tokens = new TenantGatewayTokens(JSON, twoTenants(), env()::get,
            realm(Map.of("recon-tenant-a", "TENANT_A", "recon-tenant-b", "TENANT_B"), built));

        assertThat(tokens.token("TENANT_A")).isEqualTo(jwt("TENANT_A"));
        assertThat(tokens.token("TENANT_B")).isEqualTo(jwt("TENANT_B"));
        tokens.token("TENANT_A");
        assertThat(built).as("one token source per tenant, reused").hasValue(2);
    }

    @Test
    void aTenantWithNoClientIsRefusedWithoutFallback() {
        TenantGatewayTokens tokens = new TenantGatewayTokens(JSON, twoTenants(), env()::get,
            realm(Map.of("recon-tenant-a", "TENANT_A", "recon-tenant-b", "TENANT_B"), new AtomicInteger()));

        assertThatThrownBy(() -> tokens.token("TENANT_C"))
            .isInstanceOfSatisfying(GatewayCallException.class, e -> {
                assertThat(e.code()).isEqualTo("TENANT_CREDENTIALS_MISSING");
                assertThat(e.httpStatus()).isZero();
            });
        assertThatThrownBy(() -> tokens.token(null)).isInstanceOf(GatewayCallException.class);
    }

    @Test
    void aMisMappedClientIsCaughtByTheClaimCheck() {
        // TENANT_B's entry points at a client the realm registered for TENANT_A: would bill TENANT_A.
        TenantGatewayTokens tokens = new TenantGatewayTokens(JSON, twoTenants(), env()::get,
            realm(Map.of("recon-tenant-a", "TENANT_A", "recon-tenant-b", "TENANT_A"), new AtomicInteger()));

        assertThatThrownBy(() -> tokens.token("TENANT_B"))
            .isInstanceOfSatisfying(GatewayCallException.class,
                e -> assertThat(e.code()).isEqualTo("TOKEN_TENANT_MISMATCH"));
    }

    @Test
    void aTokenWithoutAReadableTenantClaimIsRefused() {
        Map<String, TenantClient> one = Map.of("TENANT_A", new TenantClient("recon-tenant-a", "GW_SECRET_A"));
        TenantGatewayTokens noClaim = new TenantGatewayTokens(JSON, one, env()::get, c -> () -> jwt(null));
        TenantGatewayTokens opaque = new TenantGatewayTokens(JSON, one, env()::get, c -> () -> "svc-token-1");
        TenantGatewayTokens garbage = new TenantGatewayTokens(JSON, one, env()::get, c -> () -> "a.%%%.c");

        for (TenantGatewayTokens t : new TenantGatewayTokens[] {noClaim, opaque, garbage}) {
            assertThatThrownBy(() -> t.token("TENANT_A"))
                .isInstanceOfSatisfying(GatewayCallException.class,
                    e -> assertThat(e.code()).isEqualTo("TOKEN_TENANT_UNVERIFIED"));
        }
    }

    @Test
    void aKeyWhoseSeparatorsWereDroppedStillResolvesButTheClaimMustMatchExactly() {
        // Relaxed binding turned TENANT_A into TENANTA.
        Map<String, TenantClient> loose = Map.of("TENANTA", new TenantClient("recon-tenant-a", "GW_SECRET_A"));
        TenantGatewayTokens tokens = new TenantGatewayTokens(JSON, loose, env()::get, c -> () -> jwt("TENANT_A"));

        assertThat(tokens.token("TENANT_A")).isEqualTo(jwt("TENANT_A"));
        assertThatThrownBy(() -> tokens.token("tenant-a"))
            .isInstanceOfSatisfying(GatewayCallException.class,
                e -> assertThat(e.code()).isEqualTo("TOKEN_TENANT_MISMATCH"));
    }

    @Test
    void incompleteConfigurationFailsAtStartUpNamingVariablesNotValues() {
        Map<String, TenantClient> bad = new LinkedHashMap<>();
        bad.put("TENANT_A", new TenantClient("recon-tenant-a", "GW_SECRET_UNSET"));
        bad.put("TENANT_B", new TenantClient(" ", "GW_SECRET_B"));
        bad.put("tenant-b", new TenantClient("recon-tenant-b2", "GW_SECRET_B"));

        assertThatThrownBy(() -> new TenantGatewayTokens(JSON, bad, env()::get, c -> () -> "x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TENANT_A: GW_SECRET_UNSET is not set")
            .hasMessageContaining("TENANT_B: client-id and client-secret-env are required")
            .hasMessageContaining("collides with")
            .hasMessageNotContaining("secret-b");
        assertThatThrownBy(() -> new TenantGatewayTokens(JSON, Map.of(), env()::get, c -> () -> "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
